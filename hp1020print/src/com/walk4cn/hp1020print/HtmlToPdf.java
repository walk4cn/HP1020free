package com.walk4cn.hp1020print;

import android.app.Activity;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PdfCallbacks;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;

/**
 * 用 WebView 把一份 HTML 静默排版成 PDF。
 *
 * 关键点：
 *  - 不弹系统打印对话框：直接驱动 {@link PrintDocumentAdapter} 的
 *    onStart → onLayout → onWrite，把结果写进我们自己的 fd。
 *  - 整套调用必须在主线程（WebView 的要求），这里统一 post 到 MainLooper。
 *  - 分页与页边距来自 HTML 里的 @page；PrintAttributes 只负责输出分辨率和纸张。
 *  - 两个 ResultCallback 的构造器被 @hide 了，走 helper {@link PdfCallbacks} 绕开。
 */
public final class HtmlToPdf {

    private static final int TIMEOUT_MS = 25000;

    public interface Callback {
        void onDone(File pdf);

        void onError(String msg);
    }

    public static void render(final Activity act, final String html, final File out,
                              final String paper, final Callback cb) {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                go(act, html, out, paper, cb);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            new Handler(Looper.getMainLooper()).post(task);
        }
    }

    private static void go(final Activity act, final String html, final File out,
                           final String paper, final Callback cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];
        final boolean[] done = new boolean[]{false};

        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (done[0]) return;
                done[0] = true;
                cleanup(holder);
                cb.onError("生成 PDF 超时（25 秒），文档可能过于复杂");
            }
        };
        h.postDelayed(timeout, TIMEOUT_MS);

        try {
            final WebView wv = new WebView(act);
            holder[0] = wv;
            wv.getSettings().setJavaScriptEnabled(false);
            wv.getSettings().setAllowFileAccess(true);

            /* WebView 有确定宽度才肯按纸张宽度排版 */
            int wpx = Math.round(210f / 25.4f * 96f);
            if (Prefs.PAPER_LETTER.equals(paper)) wpx = Math.round(215.9f / 25.4f * 96f);
            wv.measure(View.MeasureSpec.makeMeasureSpec(wpx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            wv.layout(0, 0, wpx, Math.max(1, wv.getMeasuredHeight()));

            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (done[0]) return;
                    try {
                        writePdf(wv, out, paper, cb, new Runnable() {
                            @Override
                            public void run() {
                                done[0] = true;
                                h.removeCallbacks(timeout);
                            }
                        }, holder);
                    } catch (Throwable t) {
                        done[0] = true;
                        h.removeCallbacks(timeout);
                        cleanup(holder);
                        cb.onError("排版失败：" + t);
                    }
                }
            });

            wv.loadDataWithBaseURL(null, html, "text/html; charset=utf-8", "utf-8", null);
        } catch (Throwable t) {
            done[0] = true;
            h.removeCallbacks(timeout);
            cleanup(holder);
            cb.onError("无法初始化 WebView：" + t);
        }
    }

    private static void writePdf(final WebView wv, final File out, String paper,
                                 final Callback cb, final Runnable finished,
                                 final WebView[] holder) throws Exception {
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(Prefs.PAPER_LETTER.equals(paper)
                        ? PrintAttributes.MediaSize.NA_LETTER
                        : PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setResolution(new PrintAttributes.Resolution("hp1020", "hp1020", 300, 300))
                .build();

        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                out, ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_TRUNCATE);

        final PrintDocumentAdapter ad = wv.createPrintDocumentAdapter("hp1020-doc");
        ad.onStart();
        ad.onLayout(null, attrs, new CancellationSignal(),
                PdfCallbacks.layout(new PdfCallbacks.LayoutSink() {
                    @Override
                    public void onOk(android.print.PrintDocumentInfo info, boolean changed) {
                        ad.onWrite(new PageRange[]{PageRange.ALL_PAGES}, pfd,
                                new CancellationSignal(),
                                PdfCallbacks.write(new PdfCallbacks.WriteSink() {
                                    @Override
                                    public void onOk(PageRange[] pages) {
                                        close(pfd);
                                        finished.run();
                                        ad.onFinish();
                                        cleanup(holder);
                                        cb.onDone(out);
                                    }

                                    @Override
                                    public void onFail(CharSequence err) {
                                        close(pfd);
                                        finished.run();
                                        ad.onFinish();
                                        cleanup(holder);
                                        cb.onError("写出 PDF 失败：" + err);
                                    }
                                }));
                    }

                    @Override
                    public void onFail(CharSequence err) {
                        close(pfd);
                        finished.run();
                        ad.onFinish();
                        cleanup(holder);
                        cb.onError("排版失败：" + err);
                    }
                }), new Bundle());
    }

    private static void close(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (Exception ignored) {
        }
    }

    private static void cleanup(WebView[] holder) {
        if (holder == null || holder[0] == null) return;
        try {
            holder[0].destroy();
        } catch (Throwable ignored) {
        }
        holder[0] = null;
    }

    private HtmlToPdf() {
    }
}
