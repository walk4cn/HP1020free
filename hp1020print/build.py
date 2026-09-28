#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
手动构建 APK（不使用 Gradle，避免联网拉 AGP）：
    aapt2 compile/link -> javac -> d8 -> 注入 dex 与 .so -> zipalign -> apksigner

用法：  python build.py
"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
# aapt2 / zipalign 是 Windows 原生程序，处理不了中文路径，
# 所以先把源码搬到纯 ASCII 的临时目录里构建，完成后再把 APK 拷回来。
WORK = r"C:\Users\Lenovo\AppData\Local\Temp\hp1020apk"
SDK = r"C:\Users\Lenovo\.workbuddy\binaries\android\sdk"
BT = os.path.join(SDK, "build-tools", "34.0.0")
AJ = os.path.join(SDK, "platforms", "android-34", "android.jar")
JDK = r"C:\Users\Lenovo\.workbuddy\binaries\android\jdk-17"
JAVA = os.path.join(JDK, "bin", "java.exe")
JAVAC = os.path.join(JDK, "bin", "javac.exe")

AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
D8 = os.path.join(BT, "lib", "d8.jar")
APKSIGNER = os.path.join(BT, "lib", "apksigner.jar")
# d8.jar / apksigner.jar 没有 Main-Class，需要用 -cp 显式指定入口类，
# 并把整个 lib 目录放进 classpath 以带上依赖。
LIBCP = os.path.join(BT, "lib", "*")

BUILD = os.path.join(WORK, "build")
UNAP = os.path.join(BUILD, "app.unaligned.apk")
ALIGNED = os.path.join(BUILD, "app.aligned.apk")
OUT_APK = os.path.join(WORK, "hp1020print.apk")
FINAL_APK = os.path.join(ROOT, "HP1020无线打印.apk")
KS = os.path.join(WORK, "debug.keystore")


def run(cmd, cwd=None):
    print("    $ " + " ".join(str(c) for c in cmd)[:300], flush=True)
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        out = (r.stdout or "") + (r.stderr or "")
        print(out[-4000:])
        sys.exit("\n>>> 构建失败：%s" % cmd[0])
    return r.stdout


def main():
    print("=== 0) 搬到 ASCII 临时目录 (aapt2 不认中文路径) ===")
    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK)
    for item in ("src", "res", "jniLibs"):
        shutil.copytree(os.path.join(ROOT, item), os.path.join(WORK, item))
    shutil.copy2(os.path.join(ROOT, "AndroidManifest.xml"), WORK)
    shutil.copy2(os.path.join(ROOT, "debug.keystore"), WORK)
    print("    WORK =", WORK)

    print("\n=== 清理构建目录 ===")
    shutil.rmtree(BUILD, ignore_errors=True)
    for d in ("gen", "classes", "dex"):
        os.makedirs(os.path.join(BUILD, d), exist_ok=True)

    print("\n[1/6] aapt2 compile 资源")
    run([AAPT2, "compile", "--dir", os.path.join(WORK, "res"),
         "-o", os.path.join(BUILD, "res.zip")])

    print("\n[2/6] aapt2 link -> 未签名 APK")
    run([AAPT2, "link",
         "-I", AJ,
         "--manifest", os.path.join(WORK, "AndroidManifest.xml"),
         "-o", UNAP,
         "--java", os.path.join(BUILD, "gen"),
         "--min-sdk-version", "21",
         "--target-sdk-version", "34",
         "--auto-add-overlay",
         "-R", os.path.join(BUILD, "res.zip")])

    print("\n[3/6] javac 编译 Java")
    srcs = glob.glob(os.path.join(WORK, "src", "**", "*.java"), recursive=True)
    srcs += glob.glob(os.path.join(BUILD, "gen", "**", "*.java"), recursive=True)
    print("    源文件 %d 个" % len(srcs))
    run([JAVAC, "-encoding", "UTF-8", "-nowarn",
         "-classpath", AJ,
         "-d", os.path.join(BUILD, "classes")] + srcs)

    print("\n[4/6] d8 -> dex")
    classes = glob.glob(os.path.join(BUILD, "classes", "**", "*.class"), recursive=True)
    run([JAVA, "-cp", LIBCP, "com.android.tools.r8.D8",
         "--lib", AJ, "--min-api", "21",
         "--output", os.path.join(BUILD, "dex")] + classes)

    print("\n[5/6] 注入 dex 与原生库")
    dex = os.path.join(BUILD, "dex", "classes.dex")
    if not os.path.exists(dex):
        sys.exit("找不到 classes.dex")
    with zipfile.ZipFile(UNAP, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")
        for so in glob.glob(os.path.join(WORK, "jniLibs", "**", "*.so"), recursive=True):
            arc = "lib/" + os.path.relpath(so, os.path.join(WORK, "jniLibs")).replace("\\", "/")
            print("    + %s  (%d KB)" % (arc, os.path.getsize(so) // 1024))
            z.write(so, arc)

    print("\n[6/6] zipalign + 签名")
    run([ZIPALIGN, "-f", "4", UNAP, ALIGNED])
    run([JAVA, "-cp", LIBCP, "com.android.apksigner.ApkSignerTool", "sign",
         "--ks", KS, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey",
         "--out", OUT_APK, ALIGNED])

    print("\n=== 完成 ===")
    shutil.copy2(OUT_APK, FINAL_APK)
    print("  输出: %s  (%.1f KB)" % (FINAL_APK, os.path.getsize(FINAL_APK) / 1024.0))
    with zipfile.ZipFile(FINAL_APK) as z:
        names = z.namelist()
    for key in ("classes.dex", "lib/arm64-v8a/libzjs1020.so", "AndroidManifest.xml"):
        print("    %-32s %s" % (key, "OK" if key in names else "缺失!"))


if __name__ == "__main__":
    main()
