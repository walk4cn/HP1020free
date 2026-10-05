package com.walk4cn.hp1020print;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * 前台服务跑打印任务（分享/选文件进来的正式打印）：
 * - 离开 App / 切后台 / 锁屏都不会中断渲染与发送（ColorOS 杀不到前台服务）
 * - 通知栏常驻显示进度，结束后弹结果通知
 * - 顺带把进程暖着，缓解系统打印路径的服务绑定超时
 *
 * 参数：Uri 列表 + PrintOpts（Serializable），都从本 App 内部发起，无外部输入面。
 */
public class PrintForegroundService extends Service {

    private static final String CHANNEL_ID = "print";
    private static final int NOTIF_ID = 4711;
    private static final int RESULT_NOTIF_ID = 4712;

    private static final String EXTRA_URIS = "uris";
    private static final String EXTRA_OPTS = "opts";

    /** 打印任务串行执行（打印机一次一单），连续打印排队而不是互相打架 */
    private static final java.util.concurrent.ExecutorService EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicInteger PENDING =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static void start(Context ctx, ArrayList<Uri> uris, PrintOpts opt) {
        Intent i = new Intent(ctx, PrintForegroundService.class);
        i.putParcelableArrayListExtra(EXTRA_URIS, uris);
        i.putExtra(EXTRA_OPTS, opt);
        ctx.startForegroundService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "打印任务",
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ArrayList<Uri> uris = intent == null ? null : intent.getParcelableArrayListExtra(EXTRA_URIS);
        PrintOpts opt = intent == null ? null : (PrintOpts) intent.getSerializableExtra(EXTRA_OPTS);
        if (uris == null || uris.isEmpty() || opt == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotif("准备打印…"));
        final List<Uri> jobUris = new ArrayList<Uri>(uris);
        final PrintOpts jobOpt = opt;
        final long[] lastNotify = {0};
        PENDING.incrementAndGet();

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final String[] lastStatus = {"正在打印…"};
                PrintJob.runSync(getApplicationContext(), jobUris, jobOpt,
                        new PrintJob.Status() {
                            @Override
                            public void set(String s) {
                                lastStatus[0] = s;
                                long now = System.currentTimeMillis();
                                if (now - lastNotify[0] >= 500) {
                                    lastNotify[0] = now;
                                    notifymgr().notify(NOTIF_ID, buildNotif(s));
                                }
                            }
                        });
                finishWith(lastStatus[0]);
            }
        });
        return START_NOT_STICKY;
    }

    private void finishWith(final String status) {
        boolean ok = status != null && status.startsWith("✔");
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(ok ? "打印完成" : "打印失败")
                .setContentText(status == null ? "" : status)
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setAutoCancel(true)
                .build();
        notifymgr().notify(RESULT_NOTIF_ID, n);
        /* 还有排队的任务就保持前台，别把后面的单一起带走 */
        if (PENDING.decrementAndGet() <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private Notification buildNotif(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("HP1020 正在打印")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private NotificationManager notifymgr() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private void startForegroundCompat(Notification n) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
