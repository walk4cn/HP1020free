package com.walk4cn.hp1020print;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 打印选项页：从别处分享 / 选文件进来后先进这里，确认选项再打。
 *
 * 预览用同一套 {@link Rasterizer#computeLayout} 计算，所以预览和实际打印一致。
 * 注意 PDF 有自己的页面尺寸，这里只做「适应纸张」一个动作，
 * 缩放方式 / 方向是针对图片的选项。
 */
public class PrintOptionsActivity extends Activity {

    public static final String EXTRA_URIS = "com.walk4cn.hp1020print.URIS";

    private static final int PREVIEW_MAX_W = 360;   // dp
    private static final int THUMB_MAX = 900;       // 源图缩略图最大边

    private List<Uri> mUris;
    private PrintOpts mOpt;
    private TextView tvFile;
    private TextView tvInfo;
    private TextView tvStatus;
    private ImageView ivPreview;

    /** 预览用的源图（缩略图）或 PDF 首页 */
    private Bitmap mSrcThumb;
    private int mImgW;
    private int mImgH;
    private int mImgDpi = Rasterizer.FALLBACK_DPI;
    private boolean mIsPdf;
    private int mPdfPages = 1;

    /** Word 转换产物：原始 HTML 与生成好的 PDF */
    private boolean mIsDocx;
    private String mDocxHtml;
    private File mDocxPdf;

    private RadioGroup rgMode;
    private RadioGroup rgOri;
    private RadioGroup rgPaper;
    private EditText etZoom;
    private EditText etCopies;
    private EditText etDensity;
    private EditText etThreshold;
    private CheckBox cbRemember;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
        if (mUris == null) mUris = new ArrayList<Uri>();
        if (mUris.isEmpty()) {
            finish();
            return;
        }
        mOpt = PrintOpts.load(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pd = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pd, pd, pd, pd);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("打印选项");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setPadding(0, 0, 0, pd);
        root.addView(title);

        tvFile = line("");
        root.addView(tvFile);
        tvInfo = line("");
        tvInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(tvInfo);

        ivPreview = new ImageView(this);
        ivPreview.setBackgroundColor(0xFFE0E0E0);
        ivPreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        ivPreview.setPadding(2, 2, 2, 2);
        root.addView(ivPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(section("缩放方式（图片）"));
        rgMode = radioGroup(root,
                new String[]{"适应页面", "拉伸填满", "原始尺寸", "平铺"},
                new int[]{PrintOpts.FIT, PrintOpts.STRETCH, PrintOpts.ACTUAL, PrintOpts.TILE},
                mOpt.mode);

        etZoom = addEdit(root, "缩放 %（100 = 正好装满 / 原始大小）", String.valueOf((int) mOpt.zoom));

        root.addView(section("方向（图片）"));
        rgOri = radioGroup(root, new String[]{"自动", "纵向", "横向"},
                new int[]{PrintOpts.ORI_AUTO, PrintOpts.ORI_PORTRAIT, PrintOpts.ORI_LANDSCAPE},
                mOpt.orientation);

        root.addView(section("纸张"));
        rgPaper = radioGroup(root, new String[]{"A4", "Letter"},
                new int[]{0, 1}, Prefs.PAPER_LETTER.equals(mOpt.paper) ? 1 : 0);

        root.addView(section("其它"));
        etCopies = addEdit(root, "份数", String.valueOf(mOpt.copies));
        etDensity = addEdit(root, "浓度 1–5（越大越黑）", String.valueOf(mOpt.density));
        etThreshold = addEdit(root, "二值化阈值 0–255（越小越黑）", String.valueOf(mOpt.threshold));

        cbRemember = new CheckBox(this);
        cbRemember.setText("记住为下次的默认设置");
        cbRemember.setChecked(PrintOpts.remember(this));
        root.addView(cbRemember);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button btnPrint = new Button(this);
        btnPrint.setText("打印");
        btnPrint.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnPrint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doPrint();
            }
        });
        bar.addView(btnPrint);

        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(btnCancel);
        root.addView(bar);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, pd, 0, 0);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(tvStatus);

        setContentView(sv);

        wireRefresh();
        loadSource();
    }

    /* --------------------------- 载入源文件 --------------------------- */

    private void loadSource() {
        tvFile.setText("文件：" + PrintJob.displayName(this, mUris.get(0))
                + (mUris.size() > 1 ? " 等 " + mUris.size() + " 个" : ""));

        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = new File(getCacheDir(), "hp1020");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "preview_src");
                try {
                    PrintJob.copyUri(PrintOptionsActivity.this, mUris.get(0), f);
                    final boolean pdf = head5(f);
                    if (pdf) {
                        final Bitmap b = renderPdfFirstPage(f);
                        final int pages = countPdfPages(f);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mIsPdf = true;
                                mPdfPages = pages;
                                mSrcThumb = b;
                                setImageControlsEnabled(false);
                                refresh();
                            }
                        });
                    } else if (DocxToHtml.isDocx(f)) {
                        final File pdfOut = new File(dir, "docx.pdf");
                        try {
                            mDocxHtml = DocxToHtml.convert(f);
                        } catch (final Exception e) {
                            e.printStackTrace();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tvInfo.setText("读这个 Word 文件失败了：" + e.getMessage());
                                }
                            });
                            return;
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mIsPdf = true;
                                mIsDocx = true;
                                setImageControlsEnabled(false);
                                tvInfo.setText("正在把 Word 排版成 PDF…");
                                renderPdfFromDocx(pdfOut);
                            }
                        });
                    } else {
                        final Bitmap b = decodeThumb(f);
                        final int[] dim = Rasterizer.probeImage(f);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mIsPdf = false;
                                mSrcThumb = b;
                                mImgW = dim[0];
                                mImgH = dim[1];
                                mImgDpi = dim[2];
                                refresh();
                            }
                        });
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvInfo.setText("读不出这个文件：" + e.getMessage()
                                    + "\n（目前支持 PDF 与图片；Word/Excel 请先导出 PDF）");
                        }
                    });
                } finally {
                    f.delete();
                }
            }
        }).start();
    }

    private boolean head5(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] h = new byte[5];
            int n = in.read(h);
            return n >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F';
        } finally {
            in.close();
        }
    }

    private int countPdfPages(File f) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer r = null;
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            r = new PdfRenderer(pfd);
            return r.getPageCount();
        } catch (Exception e) {
            return 1;
        } finally {
            if (r != null) r.close();
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Bitmap renderPdfFirstPage(File f) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer r = null;
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            r = new PdfRenderer(pfd);
            int pw = 400;
            PdfRenderer.Page page = r.openPage(0);
            try {
                float ph = pw * page.getHeight() / (float) page.getWidth();
                Bitmap b = Bitmap.createBitmap(pw, Math.round(ph), Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.setScale(pw / (float) page.getWidth(), ph / (float) page.getHeight());
                page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            } finally {
                page.close();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) r.close();
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Word → PDF：用 WebView 排版，之后就把它当普通 PDF 走预览与打印 */
    private void renderPdfFromDocx(final File out) {
        if (mDocxHtml == null) return;
        tvInfo.setText("正在把 Word 排版成 PDF…");
        HtmlToPdf.render(this, DocxToHtml.wrap(mDocxHtml, mOpt.paper), out, mOpt.paper,
                new HtmlToPdf.Callback() {
                    @Override
                    public void onDone(File pdf) {
                        if (isFinishing()) return;
                        mDocxPdf = pdf;
                        List<Uri> one = new ArrayList<Uri>();
                        one.add(Uri.fromFile(pdf));
                        mUris = one;
                        mPdfPages = countPdfPages(pdf);
                        mSrcThumb = renderPdfFirstPage(pdf);
                        tvInfo.setText("Word（已排版成 PDF，共 " + mPdfPages + " 页，"
                                + "样式做了重新排版，不等同于 Word 原样）");
                        refresh();
                    }

                    @Override
                    public void onError(String msg) {
                        if (isFinishing()) return;
                        tvInfo.setText("Word 转换失败：" + msg
                                + "\n可以先用 WPS 导出成 PDF 再打印。");
                    }
                });
    }

    private Bitmap decodeThumb(File f) {
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bo);
        int max = Math.max(bo.outWidth, bo.outHeight);
        bo.inJustDecodeBounds = false;
        bo.inPreferredConfig = Bitmap.Config.ARGB_8888;
        bo.inSampleSize = Math.max(1, max / THUMB_MAX);
        return BitmapFactory.decodeFile(f.getAbsolutePath(), bo);
    }

    private void setImageControlsEnabled(boolean on) {
        for (int i = 0; i < rgMode.getChildCount(); i++) rgMode.getChildAt(i).setEnabled(on);
        for (int i = 0; i < rgOri.getChildCount(); i++) rgOri.getChildAt(i).setEnabled(on);
        etZoom.setEnabled(on);
    }

    /* --------------------------- 预览刷新 --------------------------- */

    private void wireRefresh() {
        rgMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                readUI();
                refresh();
            }
        });
        rgOri.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                readUI();
                refresh();
            }
        });
        rgPaper.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                readUI();
                if (mIsDocx) {
                    /* 纸张变了要按新的 @page size 重新排版 */
                    renderPdfFromDocx(new File(new File(getCacheDir(), "hp1020"), "docx.pdf"));
                } else {
                    refresh();
                }
            }
        });
        etZoom.addTextChangedListener(new SimpleWatcher());
        etCopies.addTextChangedListener(new SimpleWatcher());
    }

    private class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            readUI();
            refresh();
        }
    }

    /** 把界面上的值收进 mOpt */
    private void readUI() {
        mOpt.mode = tagOf(rgMode, mOpt.mode);
        mOpt.orientation = tagOf(rgOri, mOpt.orientation);
        mOpt.paper = (tagOf(rgPaper, 0) == 1) ? Prefs.PAPER_LETTER : Prefs.PAPER_A4;
        mOpt.zoom = intOf(etZoom, 100);
        mOpt.copies = Math.min(99, Math.max(1, intOf(etCopies, 1)));
        mOpt.density = Math.min(5, Math.max(1, intOf(etDensity, 3)));
        mOpt.threshold = Math.min(255, Math.max(0, intOf(etThreshold, 150)));
    }

    private void refresh() {
        if (mSrcThumb == null) return;
        drawPreview();
        updateInfo();
    }

    private void updateInfo() {
        if (mIsPdf) {
            tvInfo.setText("PDF，共 " + mPdfPages + " 页；按页面尺寸自动适应 "
                    + mOpt.paper + "。缩放方式 / 方向对 PDF 无效。");
            return;
        }
        Rasterizer.Opts ro = buildOpts();
        Rasterizer.Layout lay = Rasterizer.computeLayout(mImgW, mImgH, mImgDpi, ro);
        float[] mm = Rasterizer.unitMm(lay, ro);
        String s = "图片 " + mImgW + "×" + mImgH + "，dpi≈" + mImgDpi
                + "，打印尺寸 " + Math.round(mm[0]) + "×" + Math.round(mm[1]) + " mm";
        if (lay.rotate) s += "，内容旋转 90°";
        if (lay.tile) s += "，平铺 " + lay.cols + "×" + lay.rows;
        tvInfo.setText(s);
    }

    private Rasterizer.Opts buildOpts() {
        Rasterizer.Opts ro = new Rasterizer.Opts();
        ro.resX = Prefs.RES_X;
        ro.resY = Prefs.RES_Y;
        ro.pageW = Prefs.PAGE_W;
        ro.pageH = Prefs.PAGE_H;
        ro.paperMm = Prefs.paperSizeMmOf(mOpt.paper);
        ro.threshold = mOpt.threshold;
        ro.mode = mOpt.mode;
        ro.zoom = mOpt.zoom;
        ro.orientation = mOpt.orientation;
        return ro;
    }

    private void drawPreview() {
        float density = getResources().getDisplayMetrics().density;
        int pvW = Math.min((int) (PREVIEW_MAX_W * density),
                getResources().getDisplayMetrics().widthPixels - (int) (40 * density));
        float[] pmm = Prefs.paperSizeMmOf(mOpt.paper);
        int pvH = Math.round(pvW * pmm[1] / pmm[0]);

        Bitmap pv = Bitmap.createBitmap(pvW, pvH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(pv);
        c.drawColor(Color.WHITE);

        float cx = pvW / 2f;
        float cy = pvH / 2f;

        Rasterizer.Opts ro = buildOpts();
        Rasterizer.Layout lay;
        if (mIsPdf) {
            lay = new Rasterizer.Layout();
            lay.rotate = false;
            lay.tile = false;
            lay.cols = 1;
            lay.rows = 1;
            lay.unitW = pvW;
            lay.unitH = pvH;
            /* PDF 首页按原比例塞进预览框 */
            int iw = mSrcThumb.getWidth();
            int ih = mSrcThumb.getHeight();
            float k = Math.min(pvW / (float) iw, pvH / (float) ih);
            lay.unitW = Math.round(iw * k);
            lay.unitH = Math.round(ih * k);
        } else {
            Rasterizer.Layout real = Rasterizer.computeLayout(mImgW, mImgH, mImgDpi, ro);
            float k = pvW / (float) ro.pageW;
            lay = new Rasterizer.Layout();
            lay.rotate = real.rotate;
            lay.tile = real.tile;
            lay.cols = real.cols;
            lay.rows = real.rows;
            lay.unitW = Math.max(1, Math.round(real.unitW * k));
            lay.unitH = Math.max(1, Math.round(real.unitH * k));
        }

        int vW = lay.rotate ? pvH : pvW;
        int vH = lay.rotate ? pvW : pvH;
        float vx0 = cx - vW / 2f;
        float vy0 = cy - vH / 2f;
        float gx0 = vx0 + (vW - lay.cols * lay.unitW) / 2f;
        float gy0 = vy0 + (vH - lay.rows * lay.unitH) / 2f;

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.save();
        if (lay.rotate) c.rotate(90, cx, cy);
        for (int r = 0; r < lay.rows; r++) {
            float ty = gy0 + r * lay.unitH;
            for (int col = 0; col < lay.cols; col++) {
                float tx = gx0 + col * lay.unitW;
                c.drawBitmap(mSrcThumb, null,
                        new RectF(tx, ty, tx + lay.unitW, ty + lay.unitH), paint);
            }
        }
        c.restore();

        Paint edge = new Paint();
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(0xFF999999);
        c.drawRect(0, 0, pvW - 1, pvH - 1, edge);

        ivPreview.setImageBitmap(pv);
    }

    /* ----------------------------- 打印 ----------------------------- */

    private void doPrint() {
        readUI();
        if (cbRemember.isChecked()) mOpt.save(this);
        tvStatus.setText("已开始，见下面状态…");
        setResult(RESULT_OK);
        PrintJob.run(this, mUris, mOpt, new PrintJob.Status() {
            @Override
            public void set(final String s) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText(s);
                    }
                });
            }
        });
    }

    /* ------------------------------ UI ------------------------------ */

    private TextView line(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, 6, 0, 6);
        return t;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, 14, 0, 4);
        return t;
    }

    private RadioGroup radioGroup(LinearLayout root, String[] labels, int[] vals, int checked) {
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTag(vals[i]);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            rg.addView(rb);
            if (vals[i] == checked) rb.setChecked(true);
        }
        root.addView(rg);
        return rg;
    }

    private int tagOf(RadioGroup rg, int def) {
        int id = rg.getCheckedRadioButtonId();
        View v = rg.findViewById(id);
        if (v == null || v.getTag() == null) return def;
        return (Integer) v.getTag();
    }

    private EditText addEdit(LinearLayout root, String label, String value) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, 10, 0, 2);
        root.addView(t);

        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setSingleLine(true);
        root.addView(e);
        return e;
    }

    private int intOf(EditText e, int def) {
        try {
            return Math.round(Float.parseFloat(e.getText().toString().trim()));
        } catch (Exception ex) {
            return def;
        }
    }

}
