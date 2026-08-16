from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label}: source snippet not found")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, repl: str, label: str) -> str:
    out, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one replacement, got {count}")
    return out


# 1) True visual centering for all common back buttons.
ui_path = Path('app/src/main/java/cn/safetyledger/app/Ui.java')
ui = ui_path.read_text(encoding='utf-8')
ui = replace_once(ui, '''        android.graphics.drawable.Drawable icon = activity.getDrawable(R.drawable.ic_back_compact);
        if (icon != null) {
            int size = dp(activity, 20);
            icon.setBounds(0, 0, size, size);
            back.setCompoundDrawables(icon, null, null, null);
        }
''', '''        android.graphics.drawable.Drawable icon = activity.getDrawable(R.drawable.ic_back_compact);
        if (icon != null) {
            back.setForeground(icon);
            back.setForegroundGravity(Gravity.CENTER);
        }
''', 'center back icon')
ui_path.write_text(ui, encoding='utf-8')

# 2) Settings: plan entry is now a fully user-managed module, not fixed template rows.
settings_path = Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
settings = settings_path.read_text(encoding='utf-8')
new_plan_card = r'''    private LinearLayout monthlyPlanCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "2", "每月检查计划",
                "计划名称、统计关键词和次数全部由你自己维护"));
        TextView note = Ui.text(this,
                "不再固定使用检查模板名称。你可以自己新增“共享单车”“美团”“车棚”等项目，也可以删除、改名、调整顺序和计划次数。每个项目通过你填写的统计关键词自动计算本月实际检查次数。计划次数设为 0 时只统计，不参与完成率。",
                12, false);
        note.setTextColor(Ui.MUTED);
        card.addView(note);
        card.addView(Ui.gap(this, 7));

        List<MonthlyPlanConfig.Item> items = MonthlyPlanConfig.load(repo);
        TextView current = Ui.text(this,
                items.isEmpty() ? "当前：尚未设置计划项目"
                        : "当前：已设置 " + items.size() + " 个自定义计划项目",
                13, true);
        current.setTextColor(items.isEmpty() ? Ui.MUTED : Ui.BLUE_DARK);
        card.addView(current);
        card.addView(Ui.gap(this, 6));

        Button manage = Ui.button(this, items.isEmpty() ? "＋ 新增每月检查计划" : "管理每月检查计划");
        manage.setTextSize(14f);
        manage.setOnClickListener(view -> Ui.start(this, MonthlyPlanActivity.class));
        card.addView(manage, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        return card;
    }

'''
settings = regex_once(settings,
        r'    private LinearLayout monthlyPlanCard\(\) \{.*?\n    private LinearLayout backupCard\(\) \{',
        new_plan_card + '    private LinearLayout backupCard() {',
        'replace monthly plan settings card')
settings_path.write_text(settings, encoding='utf-8')

# 3) Ledger: refresh on return from settings/plan manager.
ledger_path = Path('app/src/main/java/cn/safetyledger/app/LedgerActivity.java')
ledger = ledger_path.read_text(encoding='utf-8')
ledger = replace_once(ledger,
'''import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
''',
'''import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
''', 'ProgressBar import')
ledger = replace_once(ledger,
'''        render();
        load();
    }

    private void render() {
''',
'''        render();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repo != null && calendarBox != null) {
            syncCalendar();
            load();
        }
    }

    private void render() {
''', 'ledger onResume refresh')

# Densify calendar slightly to give the progress panel a clearer width.
ledger = ledger.replace('grid.addView(heading, cellParams(22));', 'grid.addView(heading, cellParams(21));', 1)
ledger = ledger.replace('grid.addView(cell, cellParams(27));', 'grid.addView(cell, cellParams(25));', 1)
ledger = ledger.replace('ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 19)',
                        'ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 18)', 1)
ledger = replace_once(ledger,
        'calendarBox.addView(monthProgressPanel(), new LinearLayout.LayoutParams(Ui.dp(this, 116), ViewGroup.LayoutParams.MATCH_PARENT));',
        'calendarBox.addView(monthProgressPanel(), new LinearLayout.LayoutParams(Ui.dp(this, 122), ViewGroup.LayoutParams.MATCH_PARENT));',
        'progress panel width')

new_progress = r'''    private LinearLayout monthProgressPanel() {
        LinearLayout panel = Ui.column(this);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
        panel.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 11));

        MonthlyPlanConfig.Summary summary = MonthlyPlanConfig.summarize(repo, month);

        TextView title = Ui.text(this, "本月检查概览", 9, true);
        title.setPadding(0, 0, 0, 0);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        panel.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 18)));

        TextView total = Ui.text(this, "共检查 " + summary.totalInspections + " 次", 10, true);
        total.setPadding(0, 0, 0, 0);
        total.setGravity(Gravity.CENTER);
        total.setTextColor(Ui.BLUE_DARK);
        panel.addView(total, new LinearLayout.LayoutParams(-1, Ui.dp(this, 19)));

        if (summary.plannedTotal > 0) {
            DonutProgressView rate = new DonutProgressView(this);
            rate.setProgress(summary.percent());
            LinearLayout.LayoutParams donutParams =
                    new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
            donutParams.gravity = Gravity.CENTER_HORIZONTAL;
            panel.addView(rate, donutParams);
        } else {
            TextView noTarget = Ui.text(this, "仅统计", 10, true);
            noTarget.setGravity(Gravity.CENTER);
            noTarget.setTextColor(Ui.MUTED);
            noTarget.setPadding(0, 0, 0, 0);
            panel.addView(noTarget, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        }

        panel.addView(Ui.gap(this, 3));
        panel.addView(Ui.divider(this));
        panel.addView(Ui.gap(this, 3));

        int shown = 0;
        for (MonthlyPlanConfig.Result result : summary.results) {
            if (shown >= 2) break;
            LinearLayout itemBox = Ui.column(this);
            TextView name = Ui.text(this, compactProgressName(result.item.name), 8.5f, true);
            name.setPadding(0, 0, 0, 0);
            name.setSingleLine(true);
            name.setTextColor(Ui.TEXT);
            itemBox.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(this, 17)));

            String countText = result.item.target > 0
                    ? "已检 " + result.actual + " / " + result.item.target
                    : "已检 " + result.actual + " 次";
            TextView count = Ui.text(this, countText, 8.5f, false);
            count.setPadding(0, 0, 0, 0);
            count.setSingleLine(true);
            count.setTextColor(result.reached() ? Color.rgb(38, 177, 91) : Ui.BLUE_DARK);
            itemBox.addView(count, new LinearLayout.LayoutParams(-1, Ui.dp(this, 16)));

            if (result.item.target > 0) {
                ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(result.item.target);
                bar.setProgress(Math.min(result.actual, result.item.target));
                itemBox.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 4)));
            }
            panel.addView(itemBox);
            panel.addView(Ui.gap(this, 3));
            shown++;
        }

        if (summary.results.isEmpty()) {
            TextView empty = Ui.text(this, "未设置计划\n点击这里新增", 8.5f, false);
            empty.setPadding(0, 0, 0, 0);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Ui.MUTED);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        } else if (summary.results.size() > shown) {
            TextView more = Ui.text(this, "＋" + (summary.results.size() - shown) + " 项，点击查看", 8, false);
            more.setPadding(0, 0, 0, 0);
            more.setGravity(Gravity.CENTER);
            more.setTextColor(Ui.MUTED);
            panel.addView(more, new LinearLayout.LayoutParams(-1, Ui.dp(this, 17)));
        }

        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setOnClickListener(view -> showMonthProgressDetail(summary));
        return panel;
    }

    private void showMonthProgressDetail(MonthlyPlanConfig.Summary summary) {
        if (summary.results.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("本月检查计划")
                    .setMessage("还没有设置自定义计划项目。你可以自己新增名称、统计关键词和每月计划次数。")
                    .setPositiveButton("去设置", (dialog, which) -> Ui.start(this, MonthlyPlanActivity.class))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("本月共保存 ").append(summary.totalInspections).append(" 条正式检查记录。\n\n");
        for (MonthlyPlanConfig.Result result : summary.results) {
            message.append("• ").append(result.item.name).append("：已检查 ")
                    .append(result.actual).append(" 次");
            if (result.item.target > 0) {
                message.append(" / 计划 ").append(result.item.target).append(" 次")
                        .append("（").append(result.percent()).append("%）");
            } else {
                message.append("（只统计，不设目标）");
            }
            message.append('\n');
        }
        new AlertDialog.Builder(this)
                .setTitle("本月检查明细")
                .setMessage(message.toString().trim())
                .setPositiveButton("管理计划", (dialog, which) -> Ui.start(this, MonthlyPlanActivity.class))
                .setNegativeButton("关闭", null)
                .show();
    }

    private String compactProgressName(String name) {
        if (name == null || name.isBlank()) return "未命名";
        String value = name.trim();
        return value.length() > 7 ? value.substring(0, 7) + "…" : value;
    }

'''
ledger = regex_once(ledger,
        r'    private LinearLayout monthProgressPanel\(\) \{.*?\n    private void addProgressSpacer\(LinearLayout panel\) \{',
        new_progress + '    private void addProgressSpacer(LinearLayout panel) {',
        'replace monthly progress panel')
ledger_path.write_text(ledger, encoding='utf-8')

# 4) Register the plan manager screen.
manifest_path = Path('app/src/main/AndroidManifest.xml')
manifest = manifest_path.read_text(encoding='utf-8')
if '<activity android:name=".MonthlyPlanActivity" />' not in manifest:
    manifest = manifest.replace('<activity android:name=".SettingsActivity" />',
                                '<activity android:name=".SettingsActivity" />\n        <activity android:name=".MonthlyPlanActivity" />')
manifest_path.write_text(manifest, encoding='utf-8')

# 5) Version bump.
build_path = Path('app/build.gradle')
build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode 31', 'versionCode 32', 1)
build = build.replace("versionName '1.2.28'", "versionName '1.2.29'", 1)
build_path.write_text(build, encoding='utf-8')

Path('app/VERSION_1.2.29.txt').write_text('''安全检查台账 Android 1.2.29
- 返回箭头改用前景矢量图层，确保在白色按钮中真正水平、垂直居中
- 每月检查计划改为完全自定义项目，不再永久绑定检查模板标题
- 计划项目支持新增、删除、改名、上下调整顺序
- 每个项目可设置自由统计关键词；关键词从模板名称、检查类型、被检查单位、地点、被检查人中匹配
- 多关键词支持使用 | 或逗号分隔
- 计划次数允许 0：仅统计实际检查次数，不参与完成率
- 每条正式检查记录计 1 次，草稿不计入；取消按自然周去重
- 首页右侧显示本月总检查次数、总体计划圆环、各自定义项目“已检/计划”以及进度条
- 点击右侧本月检查概览可查看全部自定义项目详细次数，并可直接进入管理计划
- 日历进一步紧凑，为检查概览保留更清晰的显示宽度
''', encoding='utf-8')

# Keep GitHub artifact names aligned with the real app version.
workflow_path = Path('.github/workflows/android-build.yml')
if workflow_path.exists():
    workflow = workflow_path.read_text(encoding='utf-8').replace('1.2.26', '1.2.29')
    workflow_path.write_text(workflow, encoding='utf-8')
