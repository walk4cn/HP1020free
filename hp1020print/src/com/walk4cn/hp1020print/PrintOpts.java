package com.walk4cn.hp1020print;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.Serializable;

/**
 * 一次打印任务的选项。
 *
 * 可以整体塞进 Intent（Serializable），也可以存成「下次默认」（SharedPreferences）。
 * 浓度 / 阈值 / 纸张沿用 Prefs 里的同一个键，和主界面的设置是同一份数据。
 */
public final class PrintOpts implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 缩放方式 */
    public static final int FIT = 0;      // 适应页面（等比，不留空就最大）
    public static final int STRETCH = 1;  // 拉伸填满（不等比）
    public static final int ACTUAL = 2;   // 原始尺寸（按图片自带 dpi 换算，缺省按 96dpi）
    public static final int TILE = 3;     // 平铺（单元尺寸 = 适应页面 × 缩放%）

    /** 方向 */
    public static final int ORI_AUTO = 0;
    public static final int ORI_PORTRAIT = 1;
    public static final int ORI_LANDSCAPE = 2;

    private static final String K_MODE = "opt_mode";
    private static final String K_ZOOM = "opt_zoom";
    private static final String K_ORI = "opt_orientation";
    private static final String K_COPIES = "opt_copies";
    private static final String K_REMEMBER = "opt_remember";

    public int mode = FIT;
    /** 缩放百分比，100 = 正好装满页面（或原始尺寸） */
    public float zoom = 100f;
    public int orientation = ORI_AUTO;
    public int copies = 1;

    /** 由 Prefs 统一提供，这里只是任务级的快照 */
    public int density = 3;
    public int threshold = 150;
    public String paper = Prefs.PAPER_A4;

    public PrintOpts() {
    }

    /* ------------------------- 持久化 ------------------------- */

    public static PrintOpts load(Context c) {
        SharedPreferences p = Prefs.get(c);
        PrintOpts o = new PrintOpts();
        o.mode = clampi(p.getInt(K_MODE, FIT), FIT, TILE, FIT);
        o.zoom = clampf(p.getFloat(K_ZOOM, 100f), 5f, 800f, 100f);
        o.orientation = clampi(p.getInt(K_ORI, ORI_AUTO), ORI_AUTO, ORI_LANDSCAPE, ORI_AUTO);
        o.copies = clampi(p.getInt(K_COPIES, 1), 1, 99, 1);
        o.density = Prefs.density(c);
        o.threshold = Prefs.threshold(c);
        o.paper = Prefs.paper(c);
        return o;
    }

    /** 是否「记住为默认」 */
    public static boolean remember(Context c) {
        return Prefs.get(c).getBoolean(K_REMEMBER, true);
    }

    public void save(Context c) {
        Prefs.get(c).edit()
                .putInt(K_MODE, mode)
                .putFloat(K_ZOOM, zoom)
                .putInt(K_ORI, orientation)
                .putInt(K_COPIES, copies)
                .putBoolean(K_REMEMBER, true)
                .putInt(Prefs.K_DENSITY, density)
                .putInt(Prefs.K_THRESHOLD, threshold)
                .putString(Prefs.K_PAPER, paper)
                .apply();
    }

    /** 主界面改动浓度/阈值/纸张后同步进来 */
    public void syncGlobal(Context c) {
        density = Prefs.density(c);
        threshold = Prefs.threshold(c);
        paper = Prefs.paper(c);
    }

    public String modeName() {
        switch (mode) {
            case STRETCH:
                return "拉伸填满";
            case ACTUAL:
                return "原始尺寸";
            case TILE:
                return "平铺";
            default:
                return "适应页面";
        }
    }

    private static int clampi(int v, int lo, int hi, int def) {
        if (v < lo || v > hi) return def;
        return v;
    }

    private static float clampf(float v, float lo, float hi, float def) {
        if (!(v >= lo) || v > hi) return def;
        return v;
    }
}
