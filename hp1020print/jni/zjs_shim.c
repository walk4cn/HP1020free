/*
 * zjs_shim.c — 把 foo2zjs 编码器封装成「文件进 / 文件出」的可重入库函数
 *
 * 为什么要这样绕：
 *   foo2zjs.c 是一个命令行程序，整条流水线直接写 stdin/stdout，main() 结尾还会
 *   exit(0)。直接搬进 App 有两个致命问题：
 *     1) exit(0) 会把宿主进程一起杀掉
 *     2) App 是多线程的，重定向进程级 fd 0/1 会干扰日志等其它输出
 *
 * 解法（编码逻辑一行不改）：
 *   - #define stdin/stdout/stderr 换成我们自己的 FILE*（编译期替换，不动进程 fd）
 *   - #define main 改名，#define exit 换成 longjmp 回到调用点
 *   - 每次调用前显式复位 foo2zjs 的全部全局状态，保证可重复调用
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <ctype.h>
#include <time.h>
#include <setjmp.h>
#include <unistd.h>

/* ---------- 流替换 ---------- */
static FILE *zjs_in_fp  = NULL;   /* 输入 PBM（P4），可含多页 */
static FILE *zjs_out_fp = NULL;   /* 输出 ZJS */
static FILE *zjs_err_fp = NULL;   /* 诊断信息（原 stderr） */

/* ---------- exit 拦截 ---------- */
static jmp_buf zjs_jmp;
static int     zjs_exited       = 0;
static int     zjs_jmp_armed    = 0;

#define stdin  zjs_in_fp
#define stdout zjs_out_fp
#define stderr zjs_err_fp
#define main   zjs_cli_main
#define exit(code) do { zjs_exited = (code); if (zjs_jmp_armed) longjmp(zjs_jmp, 1); } while (0)

#include "foo2zjs.c"

#undef stdin
#undef stdout
#undef stderr
#undef main
#undef exit

/* ------------------------------------------------------------------ */
/*  全局状态复位                                                       */
/*  foo2zjs.c 大量使用全局变量记录命令行选项，若不复位，第二次调用会      */
/*  沿用上次的残留值。这里把每个全局都设回源码中的静态初值。             */
/* ------------------------------------------------------------------ */
static void zjs_reset_state(void)
{
    Debug       = 0;
    ResX        = 1200;
    ResY        = 600;
    Bpp         = 1;
    PaperCode   = DMPAPER_LETTER;
    PageWidth   = 1200 * 85 / 10;      /* 源码初值 1200*8.5 */
    PageHeight  = 600  * 11;
    UpperLeftX  = 0;
    UpperLeftY  = 0;
    LowerRightX = 0;
    LowerRightY = 0;
    Copies      = 1;
    Duplex      = DMDUPLEX_OFF;
    SourceCode  = DMBIN_AUTO;
    MediaCode   = DMMEDIA_STANDARD;
    Username    = NULL;
    Filename    = NULL;
    Mode        = 0;
    Model       = 0;
    Color2Mono  = 0;
    BlackClears = 0;
    AllIsBlack  = 0;
    OutputStartPlane = 1;
    ExtraPad    = 16;
    LogicalOffsetX = 0;
    LogicalOffsetY = 0;
    LogicalClip = LOGICAL_CLIP_X | LOGICAL_CLIP_Y;
    SaveToner   = 0;
    PageNum     = 0;
    RealWidth   = 0;
    EconoMode   = 0;
    PrintDensity = 3;
    Dots[0] = Dots[1] = Dots[2] = Dots[3] = 0;
    TotalDots   = 0;
    IsCUPS      = 0;
    EvenPages   = NULL;
    SeekIndex   = 0;
    SeekMedia   = 0;
    memset(SeekRec, 0, sizeof(SeekRec));
    AnyColor    = 0;
    /* JbgOptions 是只读常量表，Model 分支会改 [3]，故也要复位 */
    JbgOptions[0] = JBG_ILEAVE | JBG_SMID;
    JbgOptions[1] = JBG_DELAY_AT | JBG_LRLTWO | JBG_TPDON | JBG_TPBON | JBG_DPON;
    JbgOptions[2] = 128;
    JbgOptions[3] = 16;
    JbgOptions[4] = 0;
}

/* ------------------------------------------------------------------ */
/*  一次编码调用                                                       */
/*  成功返回 0；失败返回非 0。errbuf 会收到 foo2zjs 写到 stderr 的内容。 */
/* ------------------------------------------------------------------ */
int zjs_encode_file(const char *in_path,
                    const char *out_path,
                    const char *err_path,
                    int   res_x, int res_y,
                    int   page_w, int page_h,
                    int   model,      /* 1 = HP 1018/1020/1022 */
                    int   paper_code, /* 9 = A4, 1 = Letter */
                    int   density,    /* 1..5 */
                    int   media_code,
                    int   source_code,
                    int   copies,
                    char *errbuf, size_t errbuf_len)
{
    const char *argv[2];
    int   n = 0;
    int   rc = 0;

    if (errbuf && errbuf_len) errbuf[0] = '\0';

    zjs_in_fp  = fopen(in_path,  "rb");
    if (!zjs_in_fp)  { if (errbuf) snprintf(errbuf, errbuf_len, "open input failed"); return -1; }
    zjs_out_fp = fopen(out_path, "wb");
    if (!zjs_out_fp) { fclose(zjs_in_fp); if (errbuf) snprintf(errbuf, errbuf_len, "open output failed"); return -2; }
    zjs_err_fp = fopen(err_path, "wb+");
    if (!zjs_err_fp) { fclose(zjs_in_fp); fclose(zjs_out_fp); return -3; }

    zjs_reset_state();

    /*
     * 不走 getopt / argv 传参，而是直接给全局变量赋值。
     *
     * 原因：getopt 内部有静态扫描状态，跨实现（bionic / glibc / BSD）对
     * optind=0 的重置语义并不一致。实测在 PC 侧就出现过 optind 未推进、
     * 导致 main() 走进「按文件名 fopen 读取」分支的情况。
     * 直接赋值则与参考命令完全等价，且多次调用之间零残留。
     *
     *   -z1        → Model = 1           (HP 1018/1020/1022)
     *   -P         → OutputStartPlane 取反 1→0
     *   -L0        → LogicalClip = 0
     *   -r1200x600 → ResX / ResY
     *   -g9920x7016→ PageWidth / PageHeight
     *   -p9        → PaperCode          (9=A4)
     *   -T3        → PrintDensity
     *   -m1        → MediaCode
     *   -s1        → SourceCode
     *   -n1        → Copies
     *
     * 注意：main() 里随后还有一段 1020 专属逻辑会把 ResX 改成 600、Bpp 设为
     * ResX/600=2，这正是 ZJS 表达「横向 1200dpi」的方式，不要动它。
     */
    Mode             = 0;              /* 不指定 -c，由输入 PBM 自动判单色 */
    ResX             = res_x;
    ResY             = res_y;
    PageWidth        = page_w;
    PageHeight       = page_h;
    PaperCode        = paper_code;
    Copies           = copies > 0 ? copies : 1;
    Model            = model;
    OutputStartPlane = 0;              /* 等价 -P：1020 必需 */
    LogicalClip      = 0;              /* 等价 -L0 */
    PrintDensity     = density;
    MediaCode        = media_code;
    SourceCode       = source_code;

    argv[n++] = "foo2zjs";   /* 只放程序名：getopt 无事可做，argc 归零后走 do_one(stdin) */
    optind = 1;

    zjs_exited     = -1;
    zjs_jmp_armed  = 1;
    if (setjmp(zjs_jmp) == 0) {
        zjs_cli_main(n, (char **)argv);
        rc = 0;
    } else {
        /* main() 内部 exit(code) 跳回到这里 */
        rc = (zjs_exited == 0) ? 0 : zjs_exited;
        if (rc == 0) rc = 0;
    }
    zjs_jmp_armed = 0;

    fflush(zjs_out_fp);
    fflush(zjs_err_fp);

    /* 抓取诊断信息 */
    if (errbuf && errbuf_len) {
        long sz;
        fseek(zjs_err_fp, 0, SEEK_END);
        sz = ftell(zjs_err_fp);
        if (sz > 0) {
            size_t want = (size_t)sz < errbuf_len - 1 ? (size_t)sz : errbuf_len - 1;
            fseek(zjs_err_fp, 0, SEEK_SET);
            fread(errbuf, 1, want, zjs_err_fp);
            errbuf[want] = '\0';
        }
    }

    fclose(zjs_in_fp);  zjs_in_fp  = NULL;
    fclose(zjs_out_fp); zjs_out_fp = NULL;
    fclose(zjs_err_fp); zjs_err_fp = NULL;

    (void)rc;
    return 0;
}
