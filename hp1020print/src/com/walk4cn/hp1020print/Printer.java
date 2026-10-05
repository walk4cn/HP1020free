package com.walk4cn.hp1020print;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/** 把 ZJS 数据流发到打印服务器的 9100 端口 */
public final class Printer {

    /** 写入零进展超过此时长判定为卡死（缺纸/卡纸会让 write 永久阻塞） */
    private static final long STALL_MS = 60_000;

    /** 发送文件：局域网可达就走局域网，否则自动切远程地址（DDNS 端口映射） */
    public static void send(Context ctx, File zjs) throws IOException {
        String host = Prefs.host(ctx);
        int port = Prefs.port(ctx);
        String remoteHost = Prefs.remoteHost(ctx);
        int remotePort = Prefs.remotePort(ctx);
        if (!remoteHost.isEmpty() && remotePort > 0 && !probe(ctx, 1500)) {
            /* 家里局域网 1.5 秒连不上（人在外网），走远程映射地址 */
            host = remoteHost;
            port = remotePort;
        }
        sendTo(host, port, zjs);
    }

    private static void sendTo(String host, int port, File zjs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 15000);
            s.setSoTimeout(180000);
            OutputStream os = s.getOutputStream();
            InputStream is = new FileInputStream(zjs);

            /*
             * 写入卡死守卫：这台打印机中途拒收数据（缺纸/卡纸/出错）时，
             * write 会永久阻塞——和路由器侧 p9100d 卡 D 状态是同一个故障，
             * 靠 soTimeout 救不了（那只管 read）。60 秒零进展就关 socket，
             * 让阻塞的 write 以异常收场，而不是把打印线程永远吊死。
             */
            final AtomicLong progress = new AtomicLong();
            StallGuard guard = new StallGuard(progress, s);
            guard.start();
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    progress.addAndGet(n);
                }
                os.flush();
            } catch (IOException e) {
                if (guard.stalled()) {
                    throw new IOException("打印机超过 60 秒没有接收数据"
                            + "（可能缺纸/卡纸或通道堵死），已中断发送", e);
                }
                throw e;
            } finally {
                is.close();
                guard.shutdown();
            }
            /*
             * flush 后稍等再关：TCP 会把剩余字节继续送完，但立刻 close 可能
             * 给还在读数据的打印机回一个 RST，截断最后一段数据流。
             */
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 监视写入进度，零进展超过 STALL_MS 就关闭 socket 解锁阻塞的 write。
     * 参考实现 pocketprint 的 WRITE_STALL_MS。
     */
    private static final class StallGuard extends Thread {

        private final AtomicLong progress;
        private final Socket socket;
        private volatile boolean stopFlag;
        private volatile boolean stalled;

        StallGuard(AtomicLong progress, Socket socket) {
            this.progress = progress;
            this.socket = socket;
            setDaemon(true);
            setName("hp1020-stallguard");
        }

        @Override
        public void run() {
            long last = -1;
            long lastChange = System.currentTimeMillis();
            while (!stopFlag) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = progress.get();
                if (now != last) {
                    last = now;
                    lastChange = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastChange >= STALL_MS) {
                    stalled = true;
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                    return;
                }
            }
        }

        void shutdown() {
            stopFlag = true;
        }

        boolean stalled() {
            return stalled;
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
