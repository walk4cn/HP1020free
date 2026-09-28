package com.walk4cn.hp1020print;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 把 PDF / 图片栅格化成 PBM(P4) 位图流。
 *
 * 要点：
 *  - 1020 的引擎是横向 1200dpi / 纵向 600dpi，即像素是非方形的。
 *    PDF 走「非等比缩放」(sx≠sy)；图片走「虚拟纸张 + 整体旋转 90 度」，
 *    两种方式本质一致，最终都落到 9920 x 7016 的点阵上。
 *  - 整页一次渲染会占用 ~280MB 内存，所以按条带（strip）分块渲染，
 *    只保留一个 1bit 的行缓冲，峰值内存约 15MB。
 *  - PBM(P4) 里 1 = 黑、0 = 白，与打印机预期一致。
 */
public final class Rasterizer {

    /** 每个条带的高度（行数）。9920 x 256 的 ARGB 位图约 10MB。 */
    private static final int STRIP_ROWS = 256;

    /** 平铺时允许的最大单元数，防止 1px 平铺把 CPU 拖死 */
    private static final int MAX_TILES = 600;

    /** 图片若没写 dpi 信息，BitmapFactory 会填这个值，我们就按 96dpi 处理 */
    private static final int DEFAULT_DENSITY = 160;

    /** ACTUAL 模式下无 dpi 信息时的兜底 dpi */
    public static final int FALLBACK_DPI = 96;

    /** 一次渲染的全部参数 */
    public static final class Opts {
        public int pageW = Prefs.PAGE_W;
        public int pageH = Prefs.PAGE_H;
        public int resX = Prefs.RES_X;
        public int resY = Prefs.RES_Y;
        public float[] paperMm = new float[]{210f, 297f};
        public int threshold = 150;
        /** 缩放方式，见 PrintOpts.FIT / STRETCH / ACTUAL / TILE */
        public int mode = PrintOpts.FIT;
        public float zoom = 100f;
        public int orientation = PrintOpts.ORI_AUTO;
        /** 仅 PDF 有效：要打印的页（从 0 开始）；null 表示全部 */
        public int[] pages = null;
    }

    /** 图片在页面上的摆放结果 */
    public static final class Layout {
        /** 是否把内容整体旋转 90 度（纸张方向不变，只转内容） */
        public boolean rotate;
        /** 单个贴图的像素尺寸（在目标分辨率下） */
        public int unitW;
        public int unitH;
        public boolean tile;
        public int cols = 1;
        public int rows = 1;
    }

    public static final class Result {
        public final File pbmFile;
        public final int pageCount;

        Result(File pbmFile, int pageCount) {
            this.pbmFile = pbmFile;
            this.pageCount = pageCount;
        }
    }

    public interface Progress {
        void onPage(int done, int total);
    }

    public static Result render(File src, File outPbm, Opts o, Progress progress) throws IOException {
        if (isPdf(src)) {
            return renderPdf(src, outPbm, o, progress);
        }
        return renderImage(src, outPbm, o, progress);
    }

    /* --------------------------- 版面计算 --------------------------- */

    /**
     * 读取图片的基本信息（不解码像素）。
     *
     * @return {width, height, dpi}
     */
    public static int[] probeImage(File f) throws IOException {
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bo);
        if (bo.outWidth <= 0 || bo.outHeight <= 0) throw new IOException("不是有效的图片");
        int dpi = bo.inDensity;
        if (dpi <= 0 || dpi == DEFAULT_DENSITY) dpi = FALLBACK_DPI;
        return new int[]{bo.outWidth, bo.outHeight, dpi};
    }

    /**
     * 计算版面：unitW/unitH 是单个贴图在目标点阵上的像素尺寸。
     * 预览和真正渲染共用这套算法，所以预览所见即所得。
     */
    public static Layout computeLayout(int imgW, int imgH, int imgDpi, Opts o) {
        Layout l = new Layout();

        /*
         * 横竖必须按「物理毫米」判断，不能看像素：
         * 横向 1200dpi / 纵向 600dpi 导致 A4 的像素是 9920 x 7016（宽 > 高），
         * 但物理上它是 210mm x 297mm（宽 < 高）。
         */
        boolean paperLandscape = o.paperMm[0] > o.paperMm[1];
        boolean imgLandscape = imgW > imgH;
        if (o.orientation == PrintOpts.ORI_LANDSCAPE) {
            l.rotate = true;
        } else if (o.orientation == PrintOpts.ORI_PORTRAIT) {
            l.rotate = false;
        } else {
            /* 自动：图片与纸张横竖不一致就旋转，好充分利用纸面 */
            l.rotate = (paperLandscape != imgLandscape);
        }

        /* 旋转后真画布仍是竖版纸，先把内容排进一张「虚拟」的 (vW x vH) 纸里 */
        int vW = l.rotate ? o.pageH : o.pageW;
        int vH = l.rotate ? o.pageW : o.pageH;

        /*
         * 虚拟画布上「每方向的实际 dpi」——旋转后 x/y 会对调：
         * 旋转时虚拟 x 会落到设备的纵向（600dpi），虚拟 y 落到横向（1200dpi）。
         * 换算必须用它，否则旋转后图片比例会失真 2 倍。
         */
        float densX = l.rotate ? o.resY : o.resX;
        float densY = l.rotate ? o.resX : o.resY;

        float zoom = o.zoom / 100f;
        if (!(zoom > 0f)) zoom = 1f;

        if (o.mode == PrintOpts.STRETCH) {
            l.unitW = vW;
            l.unitH = vH;
        } else if (o.mode == PrintOpts.ACTUAL) {
            float dpi = imgDpi > 0 ? imgDpi : FALLBACK_DPI;
            l.unitW = Math.round(imgW / dpi * densX * zoom);
            l.unitH = Math.round(imgH / dpi * densY * zoom);
        } else {
            /* 先按两个方向的 dpi 把图片像素铺开，再整体缩放塞进纸面，比例才对 */
            float baseW = imgW * densX;
            float baseH = imgH * densY;
            float k = Math.min(vW / baseW, vH / baseH);
            l.unitW = Math.round(baseW * k * zoom);
            l.unitH = Math.round(baseH * k * zoom);
            l.tile = (o.mode == PrintOpts.TILE);
        }

        if (l.unitW < 1) l.unitW = 1;
        if (l.unitH < 1) l.unitH = 1;

        if (l.tile) {
            l.cols = (int) Math.ceil((float) vW / l.unitW);
            l.rows = (int) Math.ceil((float) vH / l.unitH);
            /* 单元太密会拖死 CPU：逐步放大直到数量落进预算 */
            while (l.cols * l.rows > MAX_TILES) {
                l.unitW = (int) (l.unitW * 1.3f) + 1;
                l.unitH = (int) (l.unitH * 1.3f) + 1;
                l.cols = (int) Math.ceil((float) vW / l.unitW);
                l.rows = (int) Math.ceil((float) vH / l.unitH);
            }
        }
        return l;
    }

    /** 单张贴图打出来后的物理毫米尺寸（宽 x 高），旋转时两个方向要互换 */
    public static float[] unitMm(Layout l, Opts o) {
        float w = l.rotate ? l.unitH : l.unitW;
        float h = l.rotate ? l.unitW : l.unitH;
        return new float[]{w / (float) o.resX * 25.4f, h / (float) o.resY * 25.4f};
    }

    /* ------------------------------------------------------------------ */

    private static boolean isPdf(File f) throws IOException {
        byte[] head = new byte[5];
        FileInputStream in = new FileInputStream(f);
        try {
            int n = in.read(head);
            return n >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
        } finally {
            in.close();
        }
    }

    /* ------------------------------- PDF -------------------------------- */

    private static Result renderPdf(File src, File outPbm, Opts o, Progress progress) throws IOException {

        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = null;
        OutputStream out = null;
        Bitmap strip = null;
        try {
            renderer = new PdfRenderer(pfd);
            int total = renderer.getPageCount();
            int[] list = (o.pages == null || o.pages.length == 0) ? allPages(total) : o.pages;

            out = new BufferedOutputStream(new FileOutputStream(outPbm), 1 << 16);
            int[] pixels = new int[o.pageW * STRIP_ROWS];
            int rowBytes = (o.pageW + 7) / 8;
            byte[] row = new byte[rowBytes];

            float paperWIn = o.paperMm[0] / 25.4f;
            float paperHIn = o.paperMm[1] / 25.4f;

            for (int idx = 0; idx < list.length; idx++) {
                int p = list[idx];
                if (p < 0 || p >= total) continue;

                PdfRenderer.Page page = renderer.openPage(p);
                try {
                    /* PDF 用 pt(1/72 inch)；目标位图像素非方形，故 sx≠sy */
                    float pwIn = page.getWidth() / 72f;
                    float phIn = page.getHeight() / 72f;
                    float k = Math.min(paperWIn / pwIn, paperHIn / phIn);
                    float sx = k * o.resX / 72f;
                    float sy = k * o.resY / 72f;
                    float ox = (o.pageW - page.getWidth() * sx) / 2f;
                    float oy = (o.pageH - page.getHeight() * sy) / 2f;

                    writePbmHeader(out, o.pageW, o.pageH);

                    Matrix m = new Matrix();
                    for (int y0 = 0; y0 < o.pageH; y0 += STRIP_ROWS) {
                        int h = Math.min(STRIP_ROWS, o.pageH - y0);
                        strip = ensureStrip(strip, o.pageW, h);
                        strip.eraseColor(Color.WHITE);

                        m.reset();
                        m.setScale(sx, sy);
                        /* 先缩放(pt→px)，再平移：把当前条带移到位图顶部 */
                        m.postTranslate(ox, oy - y0);
                        page.render(strip, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        writeStrip(out, strip, pixels, row, h, o);
                    }
                } finally {
                    page.close();
                }
                if (progress != null) progress.onPage(idx + 1, list.length);
            }
            out.flush();
            return new Result(outPbm, list.length);
        } finally {
            if (strip != null) strip.recycle();
            if (out != null) out.close();
            if (renderer != null) renderer.close();
            pfd.close();
        }
    }

    /* ------------------------------ 图片 -------------------------------- */

    private static Result renderImage(File src, File outPbm, Opts o, Progress progress) throws IOException {

        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bo);
        if (bo.outWidth <= 0 || bo.outHeight <= 0) {
            throw new IOException("无法识别的打印内容（既不是 PDF 也不是图片）");
        }
        int dpi = bo.inDensity;
        if (dpi <= 0 || dpi == DEFAULT_DENSITY) dpi = FALLBACK_DPI;

        bo.inJustDecodeBounds = false;
        bo.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), bo);
        if (bmp == null) {
            throw new IOException("无法识别的打印内容（既不是 PDF 也不是图片）");
        }

        Layout lay = computeLayout(bmp.getWidth(), bmp.getHeight(), dpi, o);

        OutputStream out = null;
        Bitmap strip = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(outPbm), 1 << 16);
            writePbmHeader(out, o.pageW, o.pageH);

            int[] pixels = new int[o.pageW * STRIP_ROWS];
            int rowBytes = (o.pageW + 7) / 8;
            byte[] row = new byte[rowBytes];

            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            Matrix m = new Matrix();
            float cx = o.pageW / 2f;
            float cy = o.pageH / 2f;
            int vW = lay.rotate ? o.pageH : o.pageW;
            int vH = lay.rotate ? o.pageW : o.pageH;
            float vx0 = cx - vW / 2f;
            float vy0 = cy - vH / 2f;
            /* 平铺网格整体居中 */
            float gridW = lay.cols * lay.unitW;
            float gridH = lay.rows * lay.unitH;
            float gx0 = vx0 + (vW - gridW) / 2f;
            float gy0 = vy0 + (vH - gridH) / 2f;

            for (int y0 = 0; y0 < o.pageH; y0 += STRIP_ROWS) {
                int h = Math.min(STRIP_ROWS, o.pageH - y0);
                strip = ensureStrip(strip, o.pageW, h);
                strip.eraseColor(Color.WHITE);

                Canvas c = new Canvas(strip);
                if (lay.rotate) {
                    m.setRotate(90, cx, cy);
                } else {
                    m.reset();
                }
                m.postTranslate(0, -y0);
                c.setMatrix(m);

                for (int r = 0; r < lay.rows; r++) {
                    float ty = gy0 + r * lay.unitH;
                    for (int col = 0; col < lay.cols; col++) {
                        float tx = gx0 + col * lay.unitW;
                        if (!hitsStrip(tx, ty, lay.unitW, lay.unitH, lay.rotate,
                                cx, cy, y0, h)) {
                            continue;
                        }
                        c.drawBitmap(bmp, null,
                                new RectF(tx, ty, tx + lay.unitW, ty + lay.unitH), paint);
                    }
                }

                writeStrip(out, strip, pixels, row, h, o);
            }

            out.flush();
            if (progress != null) progress.onPage(1, 1);
            return new Result(outPbm, 1);
        } finally {
            if (strip != null) strip.recycle();
            bmp.recycle();
            if (out != null) out.close();
        }
    }

    /**
     * 判断某个贴图会不会落进当前条带（用来跳过屏外的大量 drawBitmap）。
     * 旋转 90 度时映射关系是 (x,y) -> (cx - (y-cy), cy + (x-cx))，
     * 所以「垂直方向是否命中」取决于它的虚拟 x。
     */
    private static boolean hitsStrip(float x, float y, float w, float h,
                                     boolean rotate, float cx, float cy,
                                     int stripTop, int stripRows) {
        float devMin, devMax;
        if (rotate) {
            devMin = cy + (x - cx);
            devMax = cy + (x + w - cx);
            if (devMax < devMin) {
                float t = devMin;
                devMin = devMax;
                devMax = t;
            }
        } else {
            devMin = y;
            devMax = y + h;
        }
        return devMax > stripTop && devMin < stripTop + stripRows;
    }

    /* ------------------------------------------------------------------ */

    private static Bitmap ensureStrip(Bitmap old, int w, int h) {
        if (old != null && old.getWidth() == w && old.getHeight() == h) return old;
        if (old != null) old.recycle();
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    /** 把一个条带二值化后写进 PBM 流（P4：1=黑） */
    private static void writeStrip(OutputStream out, Bitmap strip, int[] pixels,
                                   byte[] row, int h, Opts o) throws IOException {
        int w = o.pageW;
        strip.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            int base = y * w;
            java.util.Arrays.fill(row, (byte) 0);
            for (int x = 0; x < w; x++) {
                int c = pixels[base + x];
                /* 近似亮度 (2R+5G+B)/8，比标准 77/151/28 快 */
                int lum = ((((c >> 16) & 0xFF) << 1)
                        + (((c >> 8) & 0xFF) * 5)
                        + (c & 0xFF)) >> 3;
                if (lum < o.threshold) {
                    row[x >> 3] |= (byte) (0x80 >> (x & 7));
                }
            }
            out.write(row);
        }
    }

    private static int[] allPages(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        return a;
    }

    private static void writePbmHeader(OutputStream out, int w, int h) throws IOException {
        out.write('P');
        out.write('4');
        out.write('\n');
        out.write(Integer.toString(w).getBytes("US-ASCII"));
        out.write(' ');
        out.write(Integer.toString(h).getBytes("US-ASCII"));
        out.write('\n');
    }

    private Rasterizer() {
    }
}
