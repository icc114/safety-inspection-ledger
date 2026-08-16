package cn.safetyledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.safetyledger.app.sync.SyncLog;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SyncLogActivity extends Activity {
    private static final int EXPORT_LOG = 901;
    private TextView logText;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        render();
    }

    private void render() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(Ui.appBar(this, "同步日志"));

        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 16));

        LinearLayout card = Ui.card(this);
        TextView note = Ui.text(this,
                "这里记录测试连接、同步准备、云端快照读取、上传/下载、合并以及具体异常。日志保存在 APP 私有目录，最多保留约 512KB，不记录同步密码、服务器登录密码、Token 或 Authorization。出现同步问题时可直接导出 TXT 发给开发者。",
                13, false);
        note.setTextColor(Ui.MUTED);
        card.addView(note);
        card.addView(Ui.gap(this, 8));

        LinearLayout actions = Ui.row(this);
        Button refresh = Ui.compactButton(this, "刷新日志", false);
        Button export = Ui.compactButton(this, "导出日志文件", true);
        Button clear = Ui.compactButton(this, "清空日志", false);
        actions.addView(refresh, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 5));
        actions.addView(export, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 5));
        actions.addView(clear, Ui.weight(1));
        card.addView(actions);
        card.addView(Ui.gap(this, 8));

        logText = Ui.text(this, "", 11, false);
        logText.setTextColor(Color.rgb(42, 52, 66));
        logText.setGravity(Gravity.TOP | Gravity.START);
        logText.setTextIsSelectable(true);
        logText.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        logText.setBackground(Ui.shape(this, Color.rgb(249, 251, 254), Ui.LINE, 9));
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 480)));

        refresh.setOnClickListener(view -> refresh());
        export.setOnClickListener(view -> chooseExport());
        clear.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("清空同步日志")
                .setMessage("仅清除诊断日志，不会删除检查记录、照片、同步配置或云端数据。")
                .setPositiveButton("清空", (dialog, which) -> {
                    SyncLog.clear(this);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show());

        content.addView(card);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refresh();
    }

    private void refresh() {
        logText.setText(SyncLog.read(this));
    }

    private void chooseExport() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "安全检查台账-同步日志-" + stamp + ".txt")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, EXPORT_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_LOG || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        new Thread(() -> {
            String failure = null;
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                SyncLog.export(this, output);
            } catch (Exception error) {
                failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
            String finalFailure = failure;
            runOnUiThread(() -> Ui.toast(this, finalFailure == null
                    ? "同步日志已导出，可以直接发送给开发者"
                    : "日志导出失败：" + finalFailure));
        }, "sync-log-export").start();
    }
}
