package cn.safetyledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.safetyledger.app.data.LedgerRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** User-editable monthly inspection targets and matching rules. */
public final class MonthlyPlanActivity extends Activity {
    private LedgerRepository repo;
    private final List<MonthlyPlanConfig.Item> draft = new ArrayList<>();
    private LinearLayout listBox;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        repo = new LedgerRepository(this);
        for (MonthlyPlanConfig.Item item : MonthlyPlanConfig.load(repo)) draft.add(item.copy());
        render();
    }

    private void render() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(Ui.appBar(this, "每月检查计划"));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 18));

        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.sectionTitle(this, "", "计划项目由你自己维护", null));
        TextView note = Ui.text(this,
                "名称可以自由填写，例如“共享单车”“美团”“车棚”。统计关键词用于从当月检查记录中识别该项目；APP 会在模板名称、检查类型、被检查单位、地点和被检查人中查找关键词。多个关键词可用 | 分隔。\n\n计划次数填 0 表示“只统计实际检查次数，不设置达标目标”；填 1、4、10 等则同时显示实际次数和计划完成率。每保存一条正式检查记录计 1 次，草稿不计入。",
                12.5f, false);
        note.setTextColor(Ui.MUTED);
        intro.addView(note);
        content.addView(intro);
        content.addView(Ui.gap(this, 10));

        listBox = Ui.column(this);
        content.addView(listBox);
        content.addView(Ui.gap(this, 8));

        Button add = Ui.secondaryButton(this, "＋ 新增计划项目");
        add.setTextSize(14f);
        add.setOnClickListener(view -> {
            draft.add(new MonthlyPlanConfig.Item(UUID.randomUUID().toString(), "", "", 1));
            renderList();
        });
        content.addView(add, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = Ui.column(this);
        bottom.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 10));
        bottom.setBackgroundColor(Ui.BG);
        Button save = Ui.button(this, "保存全部计划");
        save.setTextSize(15f);
        save.setOnClickListener(view -> savePlans());
        bottom.addView(save, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        root.addView(bottom);

        setContentView(root);
        renderList();
    }

    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        if (draft.isEmpty()) {
            LinearLayout empty = Ui.card(this);
            TextView text = Ui.text(this,
                    "还没有设置计划项目。点击下方“新增计划项目”即可自己建立。",
                    13, false);
            text.setGravity(Gravity.CENTER);
            text.setTextColor(Ui.MUTED);
            empty.addView(text);
            listBox.addView(empty);
            return;
        }

        for (int i = 0; i < draft.size(); i++) {
            MonthlyPlanConfig.Item item = draft.get(i);
            LinearLayout card = Ui.card(this);
            card.setPadding(Ui.dp(this, 11), Ui.dp(this, 9), Ui.dp(this, 11), Ui.dp(this, 9));

            LinearLayout heading = Ui.row(this);
            TextView title = Ui.text(this, "计划项目 " + (i + 1), 14, true);
            title.setGravity(Gravity.CENTER_VERTICAL);
            heading.addView(title, Ui.weight(1));

            Button up = Ui.compactButton(this, "↑", false);
            Button down = Ui.compactButton(this, "↓", false);
            Button delete = Ui.compactButton(this, "删除", false);
            up.setTextSize(15f);
            down.setTextSize(15f);
            delete.setTextSize(12f);
            final int index = i;
            up.setEnabled(i > 0);
            down.setEnabled(i < draft.size() - 1);
            up.setOnClickListener(view -> move(index, -1));
            down.setOnClickListener(view -> move(index, 1));
            delete.setOnClickListener(view -> confirmDelete(index));
            heading.addView(up, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 34)));
            heading.addView(Ui.horizontalGap(this, 4));
            heading.addView(down, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 34)));
            heading.addView(Ui.horizontalGap(this, 5));
            heading.addView(delete, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 34)));
            card.addView(heading);
            card.addView(Ui.gap(this, 7));

            TextView nameLabel = Ui.text(this, "显示名称", 12, true);
            nameLabel.setTextColor(Ui.MUTED);
            card.addView(nameLabel);
            EditText name = Ui.input(this, "例如：共享单车 / 美团 / 车棚");
            name.setSingleLine(true);
            name.setText(item.name);
            name.addTextChangedListener(watcher(value -> item.name = value));
            card.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
            card.addView(Ui.gap(this, 6));

            TextView keywordLabel = Ui.text(this, "统计关键词", 12, true);
            keywordLabel.setTextColor(Ui.MUTED);
            card.addView(keywordLabel);
            EditText keyword = Ui.input(this, "留空则使用显示名称；多个关键词用 | 分隔");
            keyword.setSingleLine(true);
            keyword.setText(item.keyword);
            keyword.addTextChangedListener(watcher(value -> item.keyword = value));
            card.addView(keyword, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
            card.addView(Ui.gap(this, 6));

            LinearLayout targetRow = Ui.row(this);
            TextView targetLabel = Ui.text(this, "每月计划次数", 13, true);
            targetLabel.setGravity(Gravity.CENTER_VERTICAL);
            EditText target = Ui.input(this, "0");
            target.setInputType(InputType.TYPE_CLASS_NUMBER);
            target.setSingleLine(true);
            target.setGravity(Gravity.CENTER);
            target.setIncludeFontPadding(false);
            target.setText(String.valueOf(item.target));
            target.addTextChangedListener(watcher(value -> {
                try {
                    item.target = Math.max(0, Math.min(MonthlyPlanConfig.MAX_TARGET,
                            Integer.parseInt(value.trim())));
                } catch (Exception ignored) {
                    if (value.isBlank()) item.target = 0;
                }
            }));
            TextView unit = Ui.text(this, "次/月", 12, false);
            unit.setGravity(Gravity.CENTER_VERTICAL);
            unit.setTextColor(Ui.MUTED);
            targetRow.addView(targetLabel, Ui.weight(1));
            targetRow.addView(target, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 42)));
            targetRow.addView(Ui.horizontalGap(this, 6));
            targetRow.addView(unit, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 42)));
            card.addView(targetRow);

            if (item.target == 0) {
                TextView onlyCount = Ui.text(this, "当前为“只统计次数”，不会影响总体计划完成率。", 11.5f, false);
                onlyCount.setTextColor(Ui.MUTED);
                card.addView(onlyCount);
            }

            listBox.addView(card);
            if (i < draft.size() - 1) listBox.addView(Ui.gap(this, 9));
        }
    }

    private TextWatcher watcher(ValueConsumer consumer) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                consumer.accept(editable == null ? "" : editable.toString());
            }
        };
    }

    private void move(int index, int direction) {
        int other = index + direction;
        if (index < 0 || index >= draft.size() || other < 0 || other >= draft.size()) return;
        MonthlyPlanConfig.Item item = draft.remove(index);
        draft.add(other, item);
        renderList();
    }

    private void confirmDelete(int index) {
        if (index < 0 || index >= draft.size()) return;
        String name = draft.get(index).name == null || draft.get(index).name.isBlank()
                ? "这个计划项目" : "“" + draft.get(index).name + "”";
        new AlertDialog.Builder(this)
                .setTitle("删除计划项目")
                .setMessage("确定从本月检查计划中删除" + name + "吗？不会删除任何检查记录。")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (index >= 0 && index < draft.size()) draft.remove(index);
                    renderList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void savePlans() {
        for (int i = 0; i < draft.size(); i++) {
            MonthlyPlanConfig.Item item = draft.get(i);
            item.name = item.name == null ? "" : item.name.trim();
            item.keyword = item.keyword == null ? "" : item.keyword.trim();
            item.target = Math.max(0, Math.min(MonthlyPlanConfig.MAX_TARGET, item.target));
            if (item.name.isBlank()) {
                Ui.toast(this, "第 " + (i + 1) + " 个计划项目还没有填写名称");
                return;
            }
            if (item.id == null || item.id.isBlank()) item.id = UUID.randomUUID().toString();
        }
        MonthlyPlanConfig.save(repo, draft);
        Ui.toast(this, "每月检查计划已保存；首页会按你的自定义项目统计本月实际次数");
        finish();
    }

    private interface ValueConsumer {
        void accept(String value);
    }
}
