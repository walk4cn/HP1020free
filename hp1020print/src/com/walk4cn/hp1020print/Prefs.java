package com.walk4cn.hp1020print;

import android.content.Context;
import android.content.SharedPreferences;

/** 打印机参数配置 */
public final class Prefs {

    private static final String NAME = "hp1020";

    public static final String K_HOST = "host";
    public static final String K_PORT = "port";
    public static final String K_DENSITY = "density";
    public static final String K_THRESHOLD = "threshold";
    public static final String K_PAPER = "paper";
    /** 远程文档转换服务器 URL（Gotenberg /forms/libreoffice/convert 兼容），空 = 不用 */
    public static final String K_CONVERT_URL = "convert_url";
    /** 远程打印地址：出门在外时走的 DDNS/公网 地址（主路由+光猫端口映射到 9100） */
    public static final String K_REMOTE_HOST = "remote_host";
    public static final String K_REMOTE_PORT = "remote_port";
    /** 系统最后一次向本服务发起「打印机发现」的时间戳（诊断用） */
    public static final String K_LAST_DISCOVERY = "last_discovery";

    /* 纸张：只保留已在实际硬件上验证过的规格 */
    public static final String PAPER_A4 = "A4";
    public static final String PAPER_LETTER = "Letter";

    /** A4 @1200x600 的栅格尺寸 —— 与 PC 上验证通过的测试页完全一致 */
    public static final int PAGE_W = 9920;
    public static final int PAGE_H = 7016;
    public static final int RES_X = 1200;
    public static final int RES_Y = 600;

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String host(Context c) {
        return get(c).getString(K_HOST, "192.168.2.120");
    }

    public static int port(Context c) {
        return get(c).getInt(K_PORT, 9100);
    }

    /** 打印浓度 1..5（越深越黑） */
    public static int density(Context c) {
        return get(c).getInt(K_DENSITY, 3);
    }

    /** 二值化阈值 0..255（越小越黑，浅色文字更容易保留） */
    public static int threshold(Context c) {
        return get(c).getInt(K_THRESHOLD, 150);
    }

    public static String paper(Context c) {
        return get(c).getString(K_PAPER, PAPER_A4);
    }

    /** 文档转换服务器 URL，空字符串 = 未配置（用离线排版） */
    public static String converterUrl(Context c) {
        return get(c).getString(K_CONVERT_URL, "").trim();
    }

    /** 远程打印地址（DDNS 主机名），空 = 未配置 */
    public static String remoteHost(Context c) {
        return get(c).getString(K_REMOTE_HOST, "").trim();
    }

    /** 远程打印端口 */
    public static int remotePort(Context c) {
        return get(c).getInt(K_REMOTE_PORT, 9100);
    }

    /** foo2zjs 的纸张代码：9=A4, 1=Letter */
    public static int paperCode(Context c) {
        return paperCodeOf(paper(c));
    }

    public static int paperCodeOf(String paper) {
        return PAPER_LETTER.equals(paper) ? 1 : 9;
    }

    /** 纸张物理尺寸（毫米） */
    public static float[] paperSizeMm(Context c) {
        return paperSizeMmOf(paper(c));
    }

    public static float[] paperSizeMmOf(String paper) {
        if (PAPER_LETTER.equals(paper)) {
            return new float[]{215.9f, 279.4f};
        }
        return new float[]{210f, 297f};
    }

    private Prefs() {
    }
}
