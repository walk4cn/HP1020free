package com.walk4cn.hp1020print;

/** foo2zjs 编码器的 JNI 封装（对应 libzjs1020.so） */
public final class ZjsNative {

    static {
        System.loadLibrary("zjs1020");
    }

    /**
     * 把 PBM(P4) 文件编码成 ZJS 打印数据流。
     *
     * @param inPath  输入的 PBM 文件（可含多页，多页头尾相接）
     * @param outPath 输出的 ZJS 文件
     * @param errPath 诊断输出文件
     * @return 0 成功，非 0 失败（同时抛 RuntimeException）
     */
    public static native int encode(String inPath, String outPath, String errPath,
                                    int resX, int resY, int pageW, int pageH,
                                    int model, int paper, int density,
                                    int media, int source, int copies);

    public static native String version();

    private ZjsNative() {
    }
}
