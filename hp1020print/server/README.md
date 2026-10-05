# 文档转换服务器（Windows 部署说明）

给 HP1020 无线打印 App 提供「Word/Excel/PPT → PDF」的高保真转换，
用本机 LibreOffice 无头模式排版。协议与 Gotenberg 兼容。

## 部署步骤（腾讯云 Windows 服务器）

1. **装 Python 3**（3.9+，微软商店或 python.org，勾选 Add to PATH）
2. **装 LibreOffice**（https://zh-cn.libreoffice.org/ 下载 Windows 版，默认安装）
3. 把 `converter_server.py` 放到比如 `C:\hp1020\`，编辑顶部两行：
   - `SOFFICE`：确认 soffice.exe 路径（默认安装就是脚本里的路径）
   - `TOKEN`：改成一段长随机字符串（必改）
4. **放行端口**：
   - 腾讯云控制台 → 安全组 → 添加入站规则：TCP 8765，来源 0.0.0.0/0
   - Windows 防火墙：`New-NetFirewallRule -DisplayName "hp1020conv" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow`（管理员 PowerShell）
5. **启动**：`python converter_server.py`
   浏览器开 `http://localhost:8765/` 看到 "alive" 即正常
6. **开机自启**（可选，管理员 PowerShell）：
   ```powershell
   schtasks /create /tn HP1020Converter /tr "python C:\hp1020\converter_server.py" /sc onstart /ru SYSTEM
   ```
   或者用 NSSM 注册成 Windows 服务（更稳，能自动重启）。

## App 端配置

App 主界面 → 「文档转换服务器 URL」填：

```
http://<服务器公网IP>:8765/forms/libreoffice/convert?t=<TOKEN>
```

保存后，从「从手机里选文件打印」选 docx/doc/xls/xlsx/ppt/pptx/rtf
会自动走服务器转换（LibreOffice 排版），转换失败自动落回离线排版（仅 docx）。

## 安全 / 隐私

- TOKEN 是唯一门槛：请改成长随机串，别用示例值
- 文档会上传到你自己的服务器，转换完临时文件立即删除
- 想更省心可以把安全组来源限定成常用出口 IP
