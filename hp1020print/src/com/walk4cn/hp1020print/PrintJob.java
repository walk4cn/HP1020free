package com.walk4cn.hp1020print;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 打印任务：Uri 列表 → PBM → ZJS → 9100 发送。
 *
 * 主界面（测试页）和选项页共用这套逻辑；参数统一由 {@link PrintOpts} 提供。
 */
public final class PrintJob {

    public interface Status {
        void set(String s);
    }

    public static void copyUri(Context c, Uri uri, File dst) throws IOException {
        InputStream is = c.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("打不开： " + uri);
        try {
            OutputStream os = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                os.close();
            }
        } finally {
            is.close();
        }
    }

    public static String displayName(Context c, Uri uri) {
        String name = null;
        try {
            if ("content".equals(uri.getScheme())) {
                Cursor cur = c.getContentResolver().query(uri, null, null, null, null);
                if (cur != null) {
                    try {
                        int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (cur.moveToFirst() && idx >= 0) name = cur.getString(idx);
                    } finally {
                        cur.close();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "文件" : name;
    }

    public static void run(final Context ctx, final List<Uri> uris, final PrintOpts opt,
                           final Status st) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                exec(app, uris, opt, st);
            }
        }).start();
    }

    /** 已有本地文件（测试页等）直接发 */
    public static void runFiles(final Context ctx, final List<File> srcFiles,
                                final PrintOpts opt, final Status st) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = new File(app.getCacheDir(), "hp1020");
                if (!dir.exists()) dir.mkdirs();
                List<File> keep = new ArrayList<File>(srcFiles);
                try {
                    render(app, dir, keep, opt, st);
                } finally {
                    for (File f : keep) f.delete();
                }
            }
        }).start();
    }

    private static void exec(Context app, List<Uri> uris, PrintOpts opt, Status st) {
        File dir = new File(app.getCacheDir(), "hp1020");
        if (!dir.exists()) dir.mkdirs();
        String tag = "job" + System.currentTimeMillis();
        List<File> files = new ArrayList<File>();
        try {
            st.set("正在读取 " + uris.size() + " 个文件…");
            for (int i = 0; i < uris.size(); i++) {
                File f = new File(dir, tag + "_in" + i);
                copyUri(app, uris.get(i), f);
                files.add(f);
            }
            render(app, dir, files, opt, st);
        } catch (final Exception e) {
            e.printStackTrace();
            st.set("读取文件失败：" + e.getMessage());
        } finally {
            for (File f : files) f.delete();
        }
    }

    /* ------------------------------------------------------------------ */

    private static void render(Context app, File dir, List<File> files, PrintOpts opt, Status st) {
        String tag = "job" + System.currentTimeMillis();
        final File pbm = new File(dir, tag + ".pbm");
        final File zjs = new File(dir, tag + ".zjs");
        final File err = new File(dir, tag + ".err");
        List<File> parts = new ArrayList<File>();

        try {
            long t0 = System.currentTimeMillis();
            st.set("正在排版…");

            Rasterizer.Opts ro = new Rasterizer.Opts();
            ro.resX = Prefs.RES_X;
            ro.resY = Prefs.RES_Y;
            ro.pageW = Prefs.PAGE_W;
            ro.pageH = Prefs.PAGE_H;
            ro.paperMm = Prefs.paperSizeMmOf(opt.paper);
            ro.threshold = opt.threshold;
            ro.mode = opt.mode;
            ro.zoom = opt.zoom;
            ro.orientation = opt.orientation;

            /* 逐个渲染；PBM(P4) 多页首尾相接即可，同一份内容重复 copies 次就是多份 */
            OutputStream out = new BufferedOutputStream(new FileOutputStream(pbm), 1 << 16);
            int pages = 0;
            try {
                for (int i = 0; i < files.size(); i++) {
                    st.set("正在排版第 " + (i + 1) + "/" + files.size() + " 个…");
                    File part = new File(dir, tag + "_p" + i + ".pbm");
                    parts.add(part);
                    Rasterizer.Result r = Rasterizer.render(files.get(i), part, ro, null);
                    for (int cp = 0; cp < Math.max(1, opt.copies); cp++) {
                        appendFile(out, part);
                        pages += r.pageCount;
                    }
                }
            } finally {
                out.close();
            }

            st.set("正在编码 " + pages + " 页…");
            int rc = ZjsNative.encode(
                    pbm.getAbsolutePath(), zjs.getAbsolutePath(), err.getAbsolutePath(),
                    Prefs.RES_X, Prefs.RES_Y, Prefs.PAGE_W, Prefs.PAGE_H,
                    1, Prefs.paperCodeOf(opt.paper),
                    opt.density, 1, 1, 1);
            if (rc != 0) {
                st.set("编码失败 rc=" + rc);
                return;
            }

            st.set("正在发送到打印机（" + pages + " 页 / " + (zjs.length() / 1024) + "KB）…");
            Printer.send(app, zjs);
            st.set("✔ 已发送 " + pages + " 页，共耗时 "
                    + ((System.currentTimeMillis() - t0) / 1000) + " 秒");
        } catch (final Exception e) {
            e.printStackTrace();
            st.set("打印失败：" + e.getMessage()
                    + "\n（目前仅支持 PDF 与图片；Word/Excel 请先导出为 PDF）");
        } finally {
            pbm.delete();
            zjs.delete();
            err.delete();
            for (File f : parts) f.delete();
        }
    }

    private static void appendFile(OutputStream out, File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    private PrintJob() {
    }
}
