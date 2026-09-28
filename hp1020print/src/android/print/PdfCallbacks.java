package android.print;

/**
 * 帮助类：让 App 代码也能直接驱动 PrintDocumentAdapter，而不弹系统打印界面。
 *
 * 原因：{@link LayoutResultCallback} / {@link WriteResultCallback} 的构造器是
 * package-private（标了 @hide），外部包的类没法继承它们，连匿名子类都写不出来。
 * 把这个类放在同名的 android.print 包下就能绕开。
 *
 * 运行时没问题：App 自己的 ClassLoader 找不到 bootclasspath 上的 android.print.PdfCallbacks，
 * 会回退到 APK 里的 dex 加载。
 */
public final class PdfCallbacks {

    public interface LayoutSink {
        void onOk(PrintDocumentInfo info, boolean changed);

        void onFail(CharSequence err);
    }

    public interface WriteSink {
        void onOk(PageRange[] pages);

        void onFail(CharSequence err);
    }

    private PdfCallbacks() {
    }

    private static final class Layout2 extends PrintDocumentAdapter.LayoutResultCallback {
        private final LayoutSink sink;

        Layout2(LayoutSink sink) {
            this.sink = sink;
        }

        @Override
        public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
            sink.onOk(info, changed);
        }

        @Override
        public void onLayoutFailed(CharSequence err) {
            sink.onFail(err);
        }

        @Override
        public void onLayoutCancelled() {
            sink.onFail("已取消");
        }
    }

    private static final class Write2 extends PrintDocumentAdapter.WriteResultCallback {
        private final WriteSink sink;

        Write2(WriteSink sink) {
            this.sink = sink;
        }

        @Override
        public void onWriteFinished(PageRange[] pages) {
            sink.onOk(pages);
        }

        @Override
        public void onWriteFailed(CharSequence err) {
            sink.onFail(err);
        }

        @Override
        public void onWriteCancelled() {
            sink.onFail("已取消");
        }
    }

    public static PrintDocumentAdapter.LayoutResultCallback layout(LayoutSink sink) {
        return new Layout2(sink);
    }

    public static PrintDocumentAdapter.WriteResultCallback write(WriteSink sink) {
        return new Write2(sink);
    }
}
