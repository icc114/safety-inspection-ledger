package cn.safetyledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;
import cn.safetyledger.app.security.PasswordHash;

public final class TrashActivity extends Activity {
    private LedgerRepository repo;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        repo = new LedgerRepository(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(Ui.appBar(this, "回收站"));
        ScrollView scroll = new ScrollView(this);
        list = Ui.column(this);
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        list.removeAllViews();
        TextView note = Ui.text(this, "普通删除的记录会保留在这里，可恢复。永久删除需要管理密码并且无法撤销。", 13, false);
        note.setTextColor(Ui.MUTED);
        list.addView(note);
        int count = 0;
        for (Inspection inspection : repo.list(null, null, null, true, 1, 100000).rows) {
            count++;
            LinearLayout card = Ui.card(this);
            card.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
            card.addView(Ui.text(this, inspection.date + " · " + inspection.templateName
                    + "\n" + inspection.location, 15, true));
            LinearLayout actions = Ui.row(this);
            Button restore = Ui.compactButton(this, "恢复记录", true);
            Button delete = Ui.dangerButton(this, "永久删除");
            restore.setOnClickListener(view -> {
                repo.restore(inspection.id);
                Ui.toast(this, "记录已恢复");
                load();
            });
            delete.setOnClickListener(view -> confirm(inspection));
            actions.addView(restore, Ui.weight(1));
            actions.addView(Ui.horizontalGap(this, 6));
            actions.addView(delete, Ui.weight(1));
            card.addView(actions);
            list.addView(card);
            list.addView(Ui.gap(this, 8));
        }
        if (count == 0) {
            TextView empty = Ui.text(this, "回收站为空", 16, true);
            empty.setTextColor(Ui.MUTED);
            empty.setGravity(android.view.Gravity.CENTER);
            list.addView(empty);
        }
    }

    private void confirm(Inspection inspection) {
        String hash = repo.setting("delete_password_hash", "");
        if (hash.isBlank()) {
            Ui.toast(this, "请先在基础设置中设置永久删除密码");
            return;
        }
        EditText password = Ui.input(this, "永久删除密码");
        password.setInputType(0x81);
        new AlertDialog.Builder(this)
                .setTitle("不可恢复的永久删除")
                .setMessage("将删除数据库记录和对应本地媒体，并建立 tombstone。此操作不可撤销。")
                .setView(password)
                .setPositiveButton("永久删除", (dialog, which) -> {
                    try {
                        if (!PasswordHash.verify(password.getText().toString().toCharArray(), hash)) {
                            Ui.toast(this, "密码错误");
                            return;
                        }
                        new MediaService(this).deleteInspectionMedia(inspection.id);
                        repo.permanentDelete(inspection.id);
                        Ui.toast(this, "已永久删除并建立 tombstone");
                        load();
                    } catch (Exception error) {
                        Ui.toast(this, "删除失败：" + error.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
