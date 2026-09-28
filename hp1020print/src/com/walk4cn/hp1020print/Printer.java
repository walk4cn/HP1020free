package com.walk4cn.hp1020print;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** 把 ZJS 数据流发到打印服务器的 9100 端口 */
public final class Printer {

    /** 发送文件 */
    public static void send(Context ctx, File zjs) throws IOException {
        String host = Prefs.host(ctx);
        int port = Prefs.port(ctx);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 15000);
            s.setSoTimeout(180000);
            OutputStream os = s.getOutputStream();
            InputStream is = new FileInputStream(zjs);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                is.close();
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 连通性测试：只连一下，不发数据 */
    public static boolean probe(Context ctx, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(Prefs.host(ctx), Prefs.port(ctx)), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Printer() {
    }
}
