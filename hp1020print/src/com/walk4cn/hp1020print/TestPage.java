package com.walk4cn.hp1020print;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 生成一张测试页并直接写成 PBM(P4)。
 *
 * 页面上画了 100mm 的横线与竖线、以及 50mm×50mm 的正方形——
 * 打印出来量一下就能判断「横向1200dpi / 纵向600dpi」的非方形像素
 * 是否被正确处理：三者若分别是 100mm / 100mm / 等边正方，即为正确。
 */
public final class TestPage {

    private static final int STRIP_ROWS = 256;

    /** A4 尺寸（pt，1pt = 1/72 inch） */
    private static final float A4_PT_W = 210f / 25.4f * 72f;
    private static final float A4_PT_H = 297f / 25.4f * 72f;

    /** 100mm / 50mm 换算成 pt */
    private static final float PT_100MM = 100f / 25.4f * 72f;
    private static final float PT_50MM = 50f / 25.4f * 72f;

    public static void write(File out, int pageW, int pageH, int threshold) throws IOException {
        float sxx = pageW / A4_PT_W;
        float syy = pageH / A4_PT_H;

        int rowBytes = (pageW + 7) / 8;
        byte[] row = new byte[rowBytes];
        int[] pixels = new int[pageW * STRIP_ROWS];

        OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
        try {
            os.write('P');
            os.write('4');
            os.write('\n');
            os.write(Integer.toString(pageW).getBytes("US-ASCII"));
            os.write(' ');
            os.write(Integer.toString(pageH).getBytes("US-ASCII"));
            os.write('\n');

            for (int y0 = 0; y0 < pageH; y0 += STRIP_ROWS) {
                int h = Math.min(STRIP_ROWS, pageH - y0);
                Bitmap bmp = Bitmap.createBitmap(pageW, h, Bitmap.Config.ARGB_8888);
                try {
                    Canvas c = new Canvas(bmp);
                    c.drawColor(Color.WHITE);
                    c.save();
                    c.scale(sxx, syy);
                    c.translate(0, -(y0 / syy));
                    draw(c);
                    c.restore();

                    bmp.getPixels(pixels, 0, pageW, 0, 0, pageW, h);
                    for (int y = 0; y < h; y++) {
                        int base = y * pageW;
                        java.util.Arrays.fill(row, (byte) 0);
                        for (int x = 0; x < pageW; x++) {
                            int argb = pixels[base + x];
                            int lum = ((((argb >> 16) & 0xFF) << 1)
                                    + (((argb >> 8) & 0xFF) * 5)
                                    + (argb & 0xFF)) >> 3;
                            if (lum < threshold) {
                                row[x >> 3] |= (byte) (0x80 >> (x & 7));
                            }
                        }
                        os.write(row);
                    }
                } finally {
                    bmp.recycle();
                }
            }
            os.flush();
        } finally {
            os.close();
        }
    }

    /* -------------------- 以 pt 为单位绘制页面内容 -------------------- */

    private static void draw(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);

        /* 标题区 */
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.BLACK);
        t.setTextSize(26f);
        c.drawText("HP LaserJet 1020 无线打印 · 测试页", 56, 92, t);

        t.setTextSize(13f);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        c.drawText("打印时间 " + date + "    来源 安卓系统打印服务", 56, 120, t);

        p.setStrokeWidth(2f);
        c.drawLine(56, 138, A4_PT_W - 56, 138, p);

        /* 标尺：横向 100mm 与纵向 100mm，打出来应等长 */
        t.setTextSize(12f);
        c.drawText("① 下方横线 = 100mm，右侧竖线 = 100mm。两者等长说明纵横比例正确。",
                56, 168, t);

        p.setStrokeWidth(2.5f);
        float bx = 56, by = 200;
        c.drawLine(bx, by, bx + PT_100MM, by, p);          /* 横 100mm */
        c.drawLine(bx, by + 18, bx, by + 18 + PT_100MM, p); /* 竖 100mm */
        t.setTextSize(10f);
        c.drawText("横 100mm", bx + 8, by - 6, t);
        c.drawText("竖 100mm", bx + 10, by + 18 + PT_100MM + 16, t);

        /* 50mm 正方形：打出来四边应相等 */
        float sx0 = 300, sy0 = 200;
        c.drawRect(sx0, sy0, sx0 + PT_50MM, sy0 + PT_50MM, p);
        t.setTextSize(10f);
        c.drawText("② 50mm×50mm 正方形（应为正方）", sx0, sy0 - 8, t);

        /* 细线宽度测试 */
        t.setTextSize(12f);
        c.drawText("③ 线宽测试：0.5 / 1 / 1.5 / 2.5 pt", 56, 330, t);
        float ly = 350;
        for (float w : new float[]{0.5f, 1f, 1.5f, 2.5f}) {
            p.setStrokeWidth(w);
            c.drawLine(56, ly, 56 + 260, ly, p);
            ly += 22;
        }

        /* 字号测试 */
        t.setTextSize(12f);
        c.drawText("④ 字号测试", 56, ly + 30, t);
        ly += 52;
        for (float sz : new float[]{8f, 10f, 14f, 20f}) {
            t.setTextSize(sz);
            c.drawText("打印测试 ABC abc 123 中文汉字 —— " + (int) sz + "pt", 56, ly, t);
            ly += sz + 14;
        }

        /* 灰度块（抖动由二值化自然产生，检查有无大面积糊死） */
        t.setTextSize(12f);
        c.drawText("⑤ 灰度块：应呈现由浅到深的层次，不应糊成一片", 56, ly + 24, t);
        float gy = ly + 38;
        Paint f = new Paint();
        for (int i = 0; i < 6; i++) {
            int v = 255 - i * 42;
            f.setColor(Color.rgb(v, v, v));
            f.setStyle(Paint.Style.FILL);
            c.drawRect(56f + i * 62f, gy, 56f + i * 62f + 56f, gy + 46f, f);
        }

        /* 说明文字 */
        t.setTextSize(11f);
        float ny = gy + 90;
        for (String line : new String[]{
                "这张页由手机本地生成：PDF/图片 → 1200×600dpi 单色位图 → JBIG 压缩 → 9100 端口。",
                "若以上①②③④⑤ 全部正常，说明整套链路（含固件自动补灌）工作正常。",
                "如果横线明显短于竖线（或正方形变成长方形），说明纵横比例参数需要调整。",
        }) {
            c.drawText(line, 56, ny, t);
            ny += 20;
        }

        /* 页脚 */
        t.setTextSize(10f);
        c.drawText("HP1020 Print Service", 56, A4_PT_H - 60, t);
    }

    private TestPage() {
    }
}
