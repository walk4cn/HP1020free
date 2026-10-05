package com.walk4cn.hp1020print;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 远程文档转换：把 Word/Excel/PPT 交给用户配置的转换服务器
 * （Gotenberg 的 /forms/libreoffice/convert 格式，或实现了同协议的自建端点），
 * 服务器用 LibreOffice 排版出 PDF，我们拿到 PDF 后走现成的栅格化管线。
 *
 * 协议：multipart/form-data，字段名 "files"，文件名带原始扩展名
 * （LibreOffice 靠它判断格式）；响应体就是 PDF 字节。
 */
public final class RemoteConverter {

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 180_000; /* LibreOffice 首次启动较慢 */

    /** 旧版 Office 的 OLE 复合文档魔数（doc/xls/ppt） */
    private static final byte[] OLE_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    private static final String[] OFFICE_EXTS = {
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp"
    };

    /** 这个文件是否属于「转换服务器能转」的办公文档类型 */
    public static boolean isConvertible(File f, String displayName) {
        if (DocxToHtml.isDocx(f)) return true;               /* docx（zip 里有 word/document.xml） */
        if (hasMagic(f, OLE_MAGIC)) return true;             /* 老式 doc/xls/ppt */
        String ext = extOf(displayName);
        for (String e : OFFICE_EXTS) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    /**
     * 上传转换并落盘 PDF。
     *
     * @return 写好的 PDF 文件（就是 out）
     * @throws IOException 网络/服务器错误，message 是可直接展示的人话
     */
    public static File convert(File src, String displayName, String url, File out)
            throws IOException {
        String boundary = "----hp1020" + Long.toHexString(System.currentTimeMillis());
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        /* 一些反向代理默认不缓存动态响应，这里明确要求直通，避免等一个被缓存的空响应 */
        conn.setRequestProperty("Cache-Control", "no-cache");

        OutputStream os = null;
        InputStream is = null;
        try {
            os = conn.getOutputStream();
            String dispName = (displayName == null || displayName.isEmpty())
                    ? "document.docx" : displayName;
            writeMultipart(os, boundary, dispName, src);
            os.flush();

            int code = conn.getResponseCode();
            if (code != 200) {
                String body = readSmall(is = conn.getErrorStream() != null
                        ? conn.getErrorStream() : conn.getInputStream());
                throw new IOException("服务器返回 " + code + (body.isEmpty() ? "" : "：" + body));
            }
            is = conn.getInputStream();

            /* 校验响应确实是 PDF（有些网关出错时也回 200 + HTML） */
            FileOutputStream fos = new FileOutputStream(out);
            byte[] head = new byte[5];
            int hn = is.read(head);
            if (hn < 5 || head[0] != '%' || head[1] != 'P' || head[2] != 'D'
                    || head[3] != 'F' || head[4] != '-') {
                fos.close();
                out.delete();
                throw new IOException("服务器返回的不是 PDF（检查转换服务是否正常）");
            }
            fos.write(head, 0, hn);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.close();
            if (out.length() == 0) {
                throw new IOException("服务器返回了空 PDF");
            }
            return out;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
            conn.disconnect();
        }
    }

    private static void writeMultipart(OutputStream os, String boundary, String fileName,
                                       File src) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"files\"; filename=\""
                + sanitize(fileName) + "\"\r\n").getBytes("UTF-8"));
        os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes("UTF-8"));
        InputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
    }

    /** 文件名里可能有引号/换行会破坏 multipart 结构，替换掉 */
    private static String sanitize(String s) {
        return s.replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static String readSmall(InputStream in) throws IOException {
        if (in == null) return "";
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0 && total < 4096) {
            bo.write(buf, 0, n);
            total += n;
        }
        return bo.toString("UTF-8").trim();
    }

    private static boolean hasMagic(File f, byte[] magic) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] h = new byte[magic.length];
            int n = in.read(h);
            if (n < magic.length) return false;
            for (int i = 0; i < magic.length; i++) {
                if (h[i] != magic[i]) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String extOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private RemoteConverter() {
    }
}
