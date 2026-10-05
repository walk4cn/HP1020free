#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HP1020 文档转换服务器（Windows / 腾讯云）
========================================

接收手机 App 上传的 Word/Excel/PPT，用本机 LibreOffice 无头模式转成 PDF 返回。
协议与 Gotenberg 的 /forms/libreoffice/convert 兼容（multipart 字段名 files），
以后如果想换 Gotenberg 容器，App 端不用改。

用法：
  1. 安装 LibreOffice（https://zh-cn.libreoffice.org/），记住 soffice.exe 路径
  2. 改下面的 TOKEN 和 SOFFICE
  3. 启动：python converter_server.py
  4. 腾讯云控制台「安全组」放行 TCP 8765；Windows 防火墙同样放行
  5. App 设置里填：http://<服务器公网IP>:8765/forms/libreoffice/convert?t=<TOKEN>

安全：TOKEN 是唯一门槛，务必改成足够长的随机串；端点只该自己人用。
隐私：文档只落在本机临时目录，转换完立即删除。
"""
import os
import re
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ====== 按需修改 ======
SOFFICE = r"C:\Program Files\LibreOffice\program\soffice.exe"
TOKEN = "please-change-me-to-a-long-random-string"   # 改掉！App URL 里的 ?t= 必须一致
PORT = 8765
CONVERT_TIMEOUT = 180        # LibreOffice 单文件超时（秒）
MAX_UPLOAD = 50 * 1024 * 1024
# =====================

# LibreOffice 无头模式同一时间只能一个实例，用锁串行化
convert_lock = threading.Lock()
ALLOWED_EXT = {".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".rtf", ".odt", ".ods", ".odp"}


def convert_to_pdf(src_path: str, out_dir: str) -> str:
    """调 LibreOffice 无头转换，返回生成的 PDF 路径"""
    cmd = [SOFFICE, "--headless", "--norestore", "--convert-to", "pdf",
           "--outdir", out_dir, src_path]
    proc = subprocess.run(cmd, capture_output=True, timeout=CONVERT_TIMEOUT,
                          creationflags=subprocess.CREATE_NO_WINDOW)
    pdf = os.path.splitext(src_path)[0] + ".pdf"
    if not os.path.exists(pdf):
        err = proc.stderr.decode("utf-8", "replace")[:300]
        raise RuntimeError("LibreOffice 转换失败 " + err)
    return pdf


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(time.strftime("[%H:%M:%S] ") + fmt % args)

    def _fail(self, code: int, msg: str):
        body = msg.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        body = ("HP1020 converter alive. POST /forms/libreoffice/convert?t=%s"
                % ("***" if TOKEN else "(no token)")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        # ---- 令牌校验：?t=xxx 或 X-Token 头 ----
        if TOKEN:
            supplied = ""
            m = re.search(r"[?&]t=([^&]+)", self.path)
            if m:
                supplied = m.group(1)
            supplied = supplied or (self.headers.get("X-Token") or "").strip()
            if supplied != TOKEN:
                self._fail(403, "token 不对")
                return

        if "/forms/libreoffice/convert" not in self.path:
            self._fail(404, "未知端点")
            return

        # ---- 读取请求体 ----
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_UPLOAD:
            self._fail(413, "空请求或超过 50MB 上限")
            return
        body = self.rfile.read(length)

        # ---- 解析 multipart，取第一个带 filename 的 part ----
        ctype = self.headers.get("Content-Type", "")
        m = re.search(r'boundary="?([^";]+)"?', ctype)
        if not m:
            self._fail(400, "不是 multipart 请求")
            return
        boundary = ("--" + m.group(1)).encode("utf-8")
        filename, payload = "document.docx", None
        for part in body.split(boundary):
            if b"filename=" not in part:
                continue
            fm = re.search(rb'filename="([^"]*)"', part)
            if fm:
                filename = fm.group(1).decode("utf-8", "replace") or filename
            head_end = part.find(b"\r\n\r\n")
            if head_end < 0:
                continue
            chunk = part[head_end + 4:]
            if chunk.endswith(b"\r\n"):
                chunk = chunk[:-2]
            if len(chunk) > 2:      # 跳过空 part
                payload = chunk
                break
        if not payload:
            self._fail(400, "没找到上传的文件（multipart 字段名必须是 files）")
            return

        ext = os.path.splitext(filename)[1].lower()
        if ext not in ALLOWED_EXT:
            self._fail(415, "不支持的类型：" + ext)
            return

        # ---- 落盘 → 转换 → 回传（临时文件用完即删） ----
        work = tempfile.mkdtemp(prefix="hp1020cv_")
        try:
            src = os.path.join(work, "file" + ext)
            with open(src, "wb") as f:
                f.write(payload)
            with convert_lock:
                pdf = convert_to_pdf(src, work)
            with open(pdf, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            print("  -> %s (%d bytes) OK" % (filename, len(data)))
        except Exception as e:
            self._fail(500, "转换失败：" + str(e))
        finally:
            for name in os.listdir(work):
                try:
                    os.remove(os.path.join(work, name))
                except OSError:
                    pass
            try:
                os.rmdir(work)
            except OSError:
                pass


if __name__ == "__main__":
    if not os.path.exists(SOFFICE):
        print("!! 找不到 LibreOffice，请安装后修改脚本顶部的 SOFFICE 路径")
        print("!! " + SOFFICE)
    print("HP1020 converter listening on 0.0.0.0:%d" % PORT)
    print("App 端请填: http://<本机公网IP>:%d/forms/libreoffice/convert?t=<TOKEN>" % PORT)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
