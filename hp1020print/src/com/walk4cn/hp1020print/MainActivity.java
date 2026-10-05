package com.walk4cn.hp1020print;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：配置打印机、打测试页、从其他 App 分享进来直接打印、从手机选文件打印。
 *
 * 说明：荣耀/华为等 ROM 的「打印」界面走自己的 Mopria 框架，只扫描网络/蓝牙打印机，
 * 不会列出第三方打印服务提供的打印机。所以「分享到本 App」是最可靠的通道。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private EditText etHost;
    private EditText etPort;
    private EditText etDensity;
    private EditText etThreshold;
    private EditText etConvert;
    private EditText etRemoteHost;
    private EditText etRemotePort;
    private TextView tvStatus;
    private TextView tvDiag;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pd = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pd, pd, pd, pd);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("HP1020 无线打印");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setPadding(0, 0, 0, pd);
        root.addView(title);

        String nv;
        try {
            nv = ZjsNative.version();
        } catch (Throwable e) {
            nv = "加载失败：" + e;
        }
        root.addView(line("编码器：" + nv));

        tvDiag = new TextView(this);
        tvDiag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvDiag.setPadding(0, 4, 0, 10);
        root.addView(tvDiag);

        root.addView(line("打印机设置"));
        etHost = addEdit(root, "IP 地址", Prefs.host(this), InputType.TYPE_CLASS_TEXT);
        etPort = addEdit(root, "端口", String.valueOf(Prefs.port(this)), InputType.TYPE_CLASS_NUMBER);
        etDensity = addEdit(root, "浓度 1–5（越大越黑）", String.valueOf(Prefs.density(this)),
                InputType.TYPE_CLASS_NUMBER);
        etThreshold = addEdit(root, "二值化阈值 0–255（越小越黑）", String.valueOf(Prefs.threshold(this)),
                InputType.TYPE_CLASS_NUMBER);
        etConvert = addEdit(root, "文档转换服务器 URL（留空用离线排版）", Prefs.converterUrl(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etRemoteHost = addEdit(root, "远程打印地址（出门在外用，DDNS 域名或 IP）",
                Prefs.remoteHost(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etRemotePort = addEdit(root, "远程打印端口（留空/0 = 不用远程）",
                String.valueOf(Prefs.remotePort(this) == 9100 ? 0 : Prefs.remotePort(this)),
                InputType.TYPE_CLASS_NUMBER);

        Button btnSave = new Button(this);
        btnSave.setText("保存设置");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        root.addView(btnSave);

        Button btnProbe = new Button(this);
        btnProbe.setText("测试连接打印机");
        btnProbe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setStatus("正在连接…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok = Printer.probe(MainActivity.this, 5000);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatus(ok
                                        ? "连接成功：" + Prefs.host(MainActivity.this) + ":" + Prefs.port(MainActivity.this)
                                        : "连接失败，请检查 IP/端口 与打印机是否开机");
                            }
                        });
                    }
                }).start();
            }
        });
        root.addView(btnProbe);

        Button btnPick = new Button(this);
        btnPick.setText("从手机里选文件打印");
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(i, "选择要打印的文件"), REQ_PICK);
            }
        });
        root.addView(btnPick);

        /*
         * 从文件管理器「用其他应用打开」发来的常是 file:// 路径，安卓 11+ 上
         * 没有所有文件访问权限就读不了（App 内选文件走 SAF 的 content:// 不受影响）。
         * 这里放一个授权入口，授权后按钮自动消失。
         */
        if (android.os.Build.VERSION.SDK_INT >= 30
                && !android.os.Environment.isExternalStorageManager()) {
            Button btnAllFiles = new Button(this);
            btnAllFiles.setText("授权「所有文件访问」（否则文件管理器直接打开会读不了）");
            btnAllFiles.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
            });
            root.addView(btnAllFiles);
        }

        Button btnTest = new Button(this);
        btnTest.setText("打印测试页");
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testPrint();
            }
        });
        root.addView(btnTest);

        Button btnSys = new Button(this);
        btnSys.setText("打开系统打印设置");
        btnSys.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.ACTION_PRINT_SETTINGS"));
                } catch (Exception e) {
                    setStatus("打不开设置页：" + e.getMessage());
                }
            }
        });
        root.addView(btnSys);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, pd, 0, 0);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(tvStatus);

        root.addView(line("\n怎么用（重要）：\n"
                + "【推荐】在微信 / QQ / 相册里打开文件 → 点「分享」或「用其他应用打开」→ 选「HP1020 无线打印」，"
                + "会先进入「打印选项」页：预览 + 缩放方式（适应/拉伸/原始尺寸/平铺）、方向、纸张、份数，"
                + "确认后点「打印」才发送。\n"
                + "也可以点上面的「从手机里选文件打印」挑本地文件，同样进选项页。\n"
                + "支持 PDF、图片、Word(.docx)；Excel 请先让 WPS 之类导出成 PDF。\n\n"
                + "注：荣耀/华为的打印界面只扫描网络与蓝牙打印机，不会列出本服务，"
                + "所以请走「分享」通道，别用系统打印菜单。"));

        setContentView(sv);

        /* 通知权限（13+）：后台打印的进度通知需要它，拒绝也不影响功能 */
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        long t = Prefs.get(this).getLong(Prefs.K_LAST_DISCOVERY, 0L);
        if (t == 0L) {
            tvDiag.setText("系统打印服务：尚未被系统调用过（不影响「分享打印」）。");
            tvDiag.setTextColor(0xFF666666);
        } else {
            tvDiag.setText("系统打印服务：已于 "
                    + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(t))
                    + " 被发现。荣耀/华为的打印界面可能不显示它，请用「分享」方式打印。");
            tvDiag.setTextColor(0xFF1B5E20);
        }
    }

    /* ------------------------ 分享 / 打开 ------------------------ */

    private void handleIntent(Intent it) {
        if (it == null) return;
        String action = it.getAction();
        List<Uri> uris = new ArrayList<Uri>();

        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> l = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri u = it.getData();
            if (u != null) uris.add(u);
        }

        if (uris.isEmpty()) return;
        /* 不再直接打印：先给选项页，用户确认后才发 */
        openOptions(uris);
    }

    private void openOptions(List<Uri> uris) {
        Intent i = new Intent(this, PrintOptionsActivity.class);
        i.putParcelableArrayListExtra(PrintOptionsActivity.EXTRA_URIS, new ArrayList<Uri>(uris));
        startActivity(i);
        setStatus("已收到 " + uris.size() + " 个文件：" + PrintJob.displayName(this, uris.get(0))
                + (uris.size() > 1 ? " 等" : "") + "\n请在选项页确认后点「打印」。");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) {
            setStatus("已选择 " + uris.size() + " 个文件。");
            openOptions(uris);
        }
    }

    /* --------------------------- 打印核心 --------------------------- */

    /* 打印链路已迁到 PrintJob（排版/编码/发送）与 PrintOptionsActivity（选项 + 预览） */

    private void testPrint() {
        save();
        setStatus("正在生成测试页…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = new File(getCacheDir(), "hp1020");
                if (!dir.exists()) dir.mkdirs();
                final File pbm = new File(dir, "test.pbm");
                final File zjs = new File(dir, "test.zjs");
                final File err = new File(dir, "test.err");
                try {
                    long t0 = System.currentTimeMillis();
                    TestPage.write(pbm, Prefs.PAGE_W, Prefs.PAGE_H, Prefs.threshold(MainActivity.this));
                    setStatus("正在编码（" + (pbm.length() / 1024 / 1024) + "MB 位图）…");

                    int rc = ZjsNative.encode(
                            pbm.getAbsolutePath(), zjs.getAbsolutePath(), err.getAbsolutePath(),
                            Prefs.RES_X, Prefs.RES_Y, Prefs.PAGE_W, Prefs.PAGE_H,
                            1, Prefs.paperCode(MainActivity.this),
                            Prefs.density(MainActivity.this), 1, 1, 1);
                    if (rc != 0) {
                        setStatus("编码失败 rc=" + rc);
                        return;
                    }
                    setStatus("正在发送到打印机（" + (zjs.length() / 1024) + "KB）…");
                    Printer.send(MainActivity.this, zjs);
                    setStatus("✔ 已发送，请检查打印机。耗时 "
                            + ((System.currentTimeMillis() - t0) / 1000) + " 秒");
                } catch (final Exception e) {
                    e.printStackTrace();
                    setStatus("失败：" + e);
                } finally {
                    pbm.delete();
                    zjs.delete();
                    err.delete();
                }
            }
        }).start();
    }

    /* ----------------------------- UI ----------------------------- */

    private TextView line(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, 8, 0, 8);
        return t;
    }

    private EditText addEdit(LinearLayout root, String label, String value, int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, 10, 0, 2);
        root.addView(t);

        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(inputType);
        e.setSingleLine(true);
        root.addView(e);
        return e;
    }

    private void save() {
        String host = etHost.getText().toString().trim();
        int port = 9100, density = 3, threshold = 150, remotePort = 0;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
            density = Integer.parseInt(etDensity.getText().toString().trim());
            threshold = Integer.parseInt(etThreshold.getText().toString().trim());
            remotePort = Integer.parseInt(etRemotePort.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        if (host.isEmpty()) host = "192.168.2.120";
        density = Math.min(5, Math.max(1, density));
        threshold = Math.min(255, Math.max(0, threshold));

        Prefs.get(this).edit()
                .putString(Prefs.K_HOST, host)
                .putInt(Prefs.K_PORT, port)
                .putInt(Prefs.K_DENSITY, density)
                .putInt(Prefs.K_THRESHOLD, threshold)
                .putString(Prefs.K_CONVERT_URL, etConvert.getText().toString().trim())
                .putString(Prefs.K_REMOTE_HOST, etRemoteHost.getText().toString().trim())
                .putInt(Prefs.K_REMOTE_PORT, Math.max(0, remotePort))
                .apply();
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvStatus.setText(s);
            }
        });
    }
}
