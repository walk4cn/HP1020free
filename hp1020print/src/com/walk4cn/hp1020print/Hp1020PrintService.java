package com.walk4cn.hp1020print;

import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 注册到安卓系统打印服务的 HP1020 打印后端。
 *
 * 一旦在「设置 → 连接 → 打印」里启用本服务，任何 App 的「打印」菜单里
 * 都能选到这台打印机，由该 App 自己把 docx/xlsx/网页 等内容渲染成 PDF
 * 交给我们，我们只负责栅格化 + 编码 + 发到 9100 端口。
 */
public class Hp1020PrintService extends PrintService {

    private static final String TAG = "Hp1020";

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new Session();
    }

    @Override
    protected void onPrintJobQueued(PrintJob job) {
        new Thread(new JobRunner(job), "hp1020-job").start();
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob job) {
        job.cancel();
    }

    /* ------------------------- 打印机发现 ------------------------- */

    private final class Session extends PrinterDiscoverySession {

        private PrinterInfo mPrinter;

        private PrinterInfo buildPrinter() {
            PrinterId id = generatePrinterId("hp1020_a4");

            PrinterCapabilitiesInfo caps = new PrinterCapabilitiesInfo.Builder(id)
                    .setMinMargins(new PrintAttributes.Margins(200, 200, 200, 200))
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addResolution(new PrintAttributes.Resolution("1200x600", "1200x600",
                            Prefs.RES_X, Prefs.RES_Y), true)
                    .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME,
                            PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE,
                            PrintAttributes.DUPLEX_MODE_NONE)
                    .build();

            String desc = Prefs.host(Hp1020PrintService.this) + ":" + Prefs.port(Hp1020PrintService.this);
            return new PrinterInfo.Builder(id, "HP LaserJet 1020（无线）",
                    PrinterInfo.STATUS_IDLE)
                    .setCapabilities(caps)
                    .setDescription(desc)
                    .build();
        }

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            Log.i(TAG, "onStartPrinterDiscovery, priority=" + priorityList);
            Prefs.get(Hp1020PrintService.this).edit()
                    .putLong(Prefs.K_LAST_DISCOVERY, System.currentTimeMillis())
                    .apply();
            mPrinter = buildPrinter();
            addPrinters(Collections.singletonList(mPrinter));
        }

        /**
         * 系统会拿之前发现过的 PrinterId 回来「验证」。这里必须重新 addPrinters，
         * 否则系统认为该打印机已失效、不会显示在打印菜单里。
         */
        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
            Log.i(TAG, "onValidatePrinters " + printerIds);
            if (mPrinter == null) {
                mPrinter = buildPrinter();
            }
            List<PrinterInfo> valid = new ArrayList<>();
            for (PrinterId id : printerIds) {
                if (mPrinter.getId().equals(id)) {
                    valid.add(mPrinter);
                }
            }
            if (!valid.isEmpty()) {
                addPrinters(valid);
            }
        }

        /** 开始跟踪状态：重新上报一次，保证打印机在列表里保持可见 */
        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
            Log.i(TAG, "onStartPrinterStateTracking " + printerId);
            if (mPrinter == null || mPrinter.getId().equals(printerId)) {
                mPrinter = buildPrinter();
            }
            addPrinters(Collections.singletonList(mPrinter));
        }

        @Override
        public void onStopPrinterDiscovery() {
            Log.i(TAG, "onStopPrinterDiscovery");
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {
        }

        @Override
        public void onDestroy() {
        }
    }

    /* ------------------------- 打印作业处理 ------------------------- */

    private final class JobRunner implements Runnable {

        private final PrintJob job;

        JobRunner(PrintJob job) {
            this.job = job;
        }

        @Override
        public void run() {
            File dir = new File(getCacheDir(), "hp1020");
            if (!dir.exists() && !dir.mkdirs()) {
                job.fail("无法创建缓存目录");
                return;
            }
            String tag = "job" + System.currentTimeMillis();
            File src = new File(dir, tag + ".src");
            File pbm = new File(dir, tag + ".pbm");
            File zjs = new File(dir, tag + ".zjs");
            File err = new File(dir, tag + ".err");

            try {
                job.start();

                /* 1) 把打印数据落盘（PdfRenderer 需要可随机访问的文件） */
                job.setStatus("正在读取文档…");
                ParcelFileDescriptor pfd = job.getDocument().getData();
                if (pfd == null) {
                    throw new IOException("拿不到打印数据");
                }
                copyToFile(pfd, src);

                int copies = Math.max(1, job.getInfo().getCopies());
                int[] pages = expandPages(job.getInfo().getPages());

                /* 2) 栅格化成 1bit 位图 */
                job.setStatus("正在排版…");
                final long t0 = System.currentTimeMillis();
                Rasterizer.Opts ro = new Rasterizer.Opts();
                ro.resX = Prefs.RES_X;
                ro.resY = Prefs.RES_Y;
                ro.pageW = Prefs.PAGE_W;
                ro.pageH = Prefs.PAGE_H;
                ro.paperMm = Prefs.paperSizeMm(Hp1020PrintService.this);
                ro.threshold = Prefs.threshold(Hp1020PrintService.this);
                ro.pages = pages;
                Rasterizer.Result r = Rasterizer.render(
                        src, pbm, ro,
                        new Rasterizer.Progress() {
                            @Override
                            public void onPage(int done, int total) {
                                job.setProgress(total <= 0 ? 0f : 0.6f * done / total);
                                job.setStatus("正在排版 " + done + "/" + total + " 页");
                            }
                        });
                Log.i(TAG, "栅格化 " + r.pageCount + " 页, 耗时 " + (System.currentTimeMillis() - t0) + "ms");

                /* 3) 编码成 ZJS */
                job.setStatus("正在编码…");
                int rc = ZjsNative.encode(
                        pbm.getAbsolutePath(), zjs.getAbsolutePath(), err.getAbsolutePath(),
                        Prefs.RES_X, Prefs.RES_Y, Prefs.PAGE_W, Prefs.PAGE_H,
                        1,                                          /* model: HP 1018/1020/1022 */
                        Prefs.paperCode(Hp1020PrintService.this),
                        Prefs.density(Hp1020PrintService.this),
                        1,                                          /* media: standard */
                        1,                                          /* source: upper tray */
                        copies);
                if (rc != 0) {
                    throw new IOException("编码失败 rc=" + rc + " " + tail(err, 400));
                }
                job.setProgress(0.8f);

                /* 4) 发到打印机 */
                job.setStatus("正在发送到打印机…");
                send(zjs);

                job.setProgress(1f);
                job.setStatus((String) null);
                job.complete();
                Log.i(TAG, "打印完成");
            } catch (Throwable e) {
                Log.e(TAG, "打印失败", e);
                String msg = e.getMessage();
                job.fail(msg == null ? e.toString() : msg);
            } finally {
                src.delete();
                pbm.delete();
                zjs.delete();
                err.delete();
            }
        }
    }

    /* ----------------------------- 工具 ----------------------------- */

    private void send(File f) throws IOException {
        Printer.send(this, f);
    }

    private static void copyToFile(ParcelFileDescriptor pfd, File dst) throws IOException {
        InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        try {
            OutputStream os = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
            }
        } finally {
            is.close();
        }
    }

    /** PageRange[] → 页码数组；全部页返回 null */
    private static int[] expandPages(PageRange[] ranges) {
        if (ranges == null || ranges.length == 0) return null;
        if (ranges.length == 1 && ranges[0].getStart() == 0
                && ranges[0].getEnd() >= Integer.MAX_VALUE - 1) {
            return null;
        }
        List<Integer> list = new ArrayList<>();
        for (PageRange r : ranges) {
            int end = r.getEnd();
            if (end >= Integer.MAX_VALUE - 1) return null;
            for (int i = r.getStart(); i <= end; i++) {
                list.add(i);
            }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out.length == 0 ? null : out;
    }

    private static String tail(File f, int max) {
        try {
            long len = f.length();
            if (len == 0) return "";
            long skip = Math.max(0, len - max);
            InputStream is = new FileInputStream(f);
            try {
                long skipped = 0;
                while (skipped < skip) {
                    long n = is.skip(skip - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                byte[] buf = new byte[(int) Math.min(len - skipped, max)];
                int off = 0;
                while (off < buf.length) {
                    int n = is.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(buf, 0, off, "UTF-8");
            } finally {
                is.close();
            }
        } catch (IOException e) {
            return "";
        }
    }
}
