/*
 * zjs_jni.c — Java 与 foo2zjs 编码器之间的薄桥接层
 *
 * 只负责：把 Java 传来的路径/参数转交 zjs_encode_file()，失败时抛异常。
 * 数据本身走文件（App 的 cacheDir），避免大块内存跨 JNI 拷贝。
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

int zjs_encode_file(const char *in_path, const char *out_path, const char *err_path,
                    int res_x, int res_y, int page_w, int page_h,
                    int model, int paper_code, int density,
                    int media_code, int source_code, int copies,
                    char *errbuf, size_t errbuf_len);

JNIEXPORT jint JNICALL
Java_com_walk4cn_hp1020print_ZjsNative_encode(JNIEnv *env, jclass clazz,
        jstring jIn, jstring jOut, jstring jErr,
        jint resX, jint resY, jint pageW, jint pageH,
        jint model, jint paper, jint density,
        jint media, jint source, jint copies)
{
    const char *in  = (*env)->GetStringUTFChars(env, jIn,  NULL);
    const char *out = (*env)->GetStringUTFChars(env, jOut, NULL);
    const char *err = (*env)->GetStringUTFChars(env, jErr, NULL);
    char errbuf[4096];
    int  rc;

    if (!in || !out || !err) {
        if (in)  (*env)->ReleaseStringUTFChars(env, jIn,  in);
        if (out) (*env)->ReleaseStringUTFChars(env, jOut, out);
        if (err) (*env)->ReleaseStringUTFChars(env, jErr, err);
        return -100;
    }

    rc = zjs_encode_file(in, out, err,
                         (int)resX, (int)resY, (int)pageW, (int)pageH,
                         (int)model, (int)paper, (int)density,
                         (int)media, (int)source, (int)copies,
                         errbuf, sizeof(errbuf));

    (*env)->ReleaseStringUTFChars(env, jIn,  in);
    (*env)->ReleaseStringUTFChars(env, jOut, out);
    (*env)->ReleaseStringUTFChars(env, jErr, err);

    if (rc != 0) {
        jclass ex = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (ex) {
            (*env)->ThrowNew(env, ex, errbuf[0] ? errbuf : "zjs encode failed");
        }
    }
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_walk4cn_hp1020print_ZjsNative_version(JNIEnv *env, jclass clazz)
{
    return (*env)->NewStringUTF(env, "foo2zjs-shim 1.0 (HP1020 -z1 -P -L0)");
}
