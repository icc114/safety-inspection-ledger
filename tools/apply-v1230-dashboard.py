from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        if new in text:
            print(f"already updated: {path}")
            return
        raise SystemExit(f"expected text not found in {path}: {old[:80]!r}")
    write(path, text.replace(old, new, 1))
    print(f"updated: {path}")


def replace_between(path, start, end, replacement):
    text = read(path)
    i = text.find(start)
    if i < 0:
        if replacement.strip() in text:
            print(f"already updated block: {path}")
            return
        raise SystemExit(f"start marker not found in {path}")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"end marker not found in {path}")
    write(path, text[:i] + replacement + "\n\n" + text[j:])
    print(f"updated block: {path}")


# Version.
replace_once(
    "app/build.gradle",
    "versionCode 32\n        versionName '1.2.29'",
    "versionCode 33\n        versionName '1.2.30'",
)

# Fill page title: make it the concise product wording requested by the user.
replace_once(
    "app/src/main/java/cn/safetyledger/app/MainActivity.java",
    'TextView topTitle = Ui.text(this, "本地检查表\\n检查填报", 20, true);',
    'TextView topTitle = Ui.text(this, "检查填表", 21, true);\n        topTitle.setGravity(Gravity.CENTER_VERTICAL);',
)

# Explain the independent natural-week warning in settings.
replace_once(
    "app/src/main/java/cn/safetyledger/app/SettingsActivity.java",
    '"不再固定使用检查模板名称。你可以自己新增“共享单车”“美团”“车棚”等项目，也可以删除、改名、调整顺序和计划次数。每个项目通过你填写的统计关键词自动计算本月实际检查次数。计划次数设为 0 时只统计，不参与完成率。",',
    '"不再固定使用任何检查项目名称。你可以自己新增、删除、改名和调整顺序，并设置统计关键词与月度目标。计划次数设为 0 时只统计实际次数，不参与完成率。首页另按自然周（周一至周日）提示漏检：跨月周归属于周一所在月份，尚未结束的本周不会提前判定漏检。",',
)

replace_once(
    "app/src/main/java/cn/safetyledger/app/MonthlyPlanActivity.java",
    '"名称可以自由填写，例如“共享单车”“美团”“车棚”。统计关键词用于从当月检查记录中识别该项目；APP 会在模板名称、检查类型、被检查单位、地点和被检查人中查找关键词。多个关键词可用 | 分隔。\\n\\n计划次数填 0 表示“只统计实际检查次数，不设置达标目标”；填 1、4、10 等则同时显示实际次数和计划完成率。每保存一条正式检查记录计 1 次，草稿不计入。",',
    '"名称完全由你自己填写，例如“共享单车”“美团”“车棚”，可新增、删除、改名和调整顺序。统计关键词用于从当月检查记录中识别该项目；APP 会在模板名称、检查类型、被检查单位、地点和被检查人中查找关键词，多个关键词可用 | 分隔。\\n\\n计划次数填 0 表示只统计实际检查次数；填 1、4、10 等则同时显示目标、已检次数和完成率。每保存一条正式检查记录计 1 次，草稿不计入。首页的红色“漏”是独立的自然周提醒：周一至周日为一周，跨月周归属于周一所在月份，本周未结束前不会判定漏检。",',
)

# Rebuild the calendar-side monthly dashboard.
progress_block = r'''    private LinearLayout monthProgressPanel() {
        LinearLayout panel = Ui.column(this);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4));
        panel.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 11));

        MonthlyPlanConfig.Summary summary = MonthlyPlanConfig.summarize(repo, month);

        TextView title = Ui.text(this, "本月检查进度", 9, true);
        title.setPadding(0, 0, 0, 0);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        panel.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 18)));

        if (summary.plannedTotal > 0) {
            DonutProgressView rate = new DonutProgressView(this);
            rate.setProgress(summary.percent());
            LinearLayout.LayoutParams donutParams =
                    new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56));
            donutParams.gravity = Gravity.CENTER_HORIZONTAL;
            panel.addView(rate, donutParams);
        } else {
            TextView onlyCount = Ui.text(this, "本月已检\n" + summary.totalInspections + " 次", 10, true);
            onlyCount.setGravity(Gravity.CENTER);
            onlyCount.setTextColor(Ui.BLUE_DARK);
            onlyCount.setPadding(0, 0, 0, 0);
            panel.addView(onlyCount, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        }

        LinearLayout overall = Ui.row(this);
        overall.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = Ui.text(this, "", 8.5f, true);
        count.setText(planCountSpan(summary.plannedTotal, summary.actualAgainstPlan));
        count.setPadding(0, 0, 0, 0);
        count.setSingleLine(true);
        count.setGravity(Gravity.CENTER_VERTICAL);
        overall.addView(count, new LinearLayout.LayoutParams(0, Ui.dp(this, 26), 1));
        if (summary.hasMissedWeek()) {
            overall.addView(statusBadge("漏", Color.rgb(218, 57, 62)),
                    new LinearLayout.LayoutParams(Ui.dp(this, 25), Ui.dp(this, 25)));
        } else if (summary.reached()) {
            overall.addView(statusBadge("✓", Color.rgb(38, 177, 91)),
                    new LinearLayout.LayoutParams(Ui.dp(this, 25), Ui.dp(this, 25)));
        }
        panel.addView(overall);

        panel.addView(Ui.gap(this, 2));
        panel.addView(Ui.divider(this));
        panel.addView(Ui.gap(this, 2));

        int shown = 0;
        for (MonthlyPlanConfig.Result result : summary.results) {
            if (shown >= 2) break;
            LinearLayout item = Ui.row(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout textBox = Ui.column(this);
            TextView name = Ui.text(this, compactProgressName(result.item.name), 8, true);
            name.setPadding(0, 0, 0, 0);
            name.setSingleLine(true);
            name.setTextColor(Ui.TEXT);
            textBox.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(this, 16)));

            TextView itemCount = Ui.text(this, "", 8, false);
            itemCount.setPadding(0, 0, 0, 0);
            itemCount.setSingleLine(true);
            if (result.item.target > 0) {
                itemCount.setText(planCountSpan(result.item.target, result.actual));
            } else {
                itemCount.setText("已检 " + result.actual + " 次");
                itemCount.setTextColor(Ui.BLUE_DARK);
            }
            textBox.addView(itemCount, new LinearLayout.LayoutParams(-1, Ui.dp(this, 16)));
            item.addView(textBox, new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1));
            if (result.reached()) {
                TextView checked = Ui.text(this, "✓", 12, true);
                checked.setGravity(Gravity.CENTER);
                checked.setTextColor(Color.rgb(38, 177, 91));
                checked.setPadding(0, 0, 0, 0);
                item.addView(checked, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 34)));
            }
            panel.addView(item);
            shown++;
        }

        if (summary.results.isEmpty()) {
            TextView empty = Ui.text(this, "未设置计划\n点击这里新增", 8, false);
            empty.setPadding(0, 0, 0, 0);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Ui.MUTED);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));
        } else if (summary.results.size() > shown) {
            TextView more = Ui.text(this, "＋" + (summary.results.size() - shown) + " 项，点击查看", 8, false);
            more.setPadding(0, 0, 0, 0);
            more.setGravity(Gravity.CENTER);
            more.setTextColor(Ui.MUTED);
            panel.addView(more, new LinearLayout.LayoutParams(-1, Ui.dp(this, 16)));
        }

        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setOnClickListener(view -> showMonthProgressDetail(summary));
        return panel;
    }

    private SpannableStringBuilder planCountSpan(int target, int actual) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        text.append("目标 ");
        int targetStart = text.length();
        text.append(String.valueOf(target));
        text.setSpan(new ForegroundColorSpan(Ui.BLUE_DARK), targetStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(" / 已检 ");
        int actualStart = text.length();
        text.append(String.valueOf(actual));
        text.setSpan(new ForegroundColorSpan(Color.rgb(38, 177, 91)), actualStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private TextView statusBadge(String value, int color) {
        TextView badge = Ui.text(this, value, 10, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(0, 0, 0, 0);
        badge.setIncludeFontPadding(false);
        badge.setTextColor(Color.WHITE);
        badge.setBackground(Ui.shape(this, color, Color.TRANSPARENT, 20));
        return badge;
    }

    private void showMonthProgressDetail(MonthlyPlanConfig.Summary summary) {
        if (summary.results.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("本月检查计划")
                    .setMessage("还没有设置自定义计划项目。计划名称、统计关键词、目标次数和顺序都由你自己维护。")
                    .setPositiveButton("去设置", (dialog, which) -> Ui.start(this, MonthlyPlanActivity.class))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        StringBuilder message = new StringBuilder();
        if (summary.plannedTotal > 0) {
            message.append("本月目标 ").append(summary.plannedTotal)
                    .append(" 次，已检查 ").append(summary.actualAgainstPlan)
                    .append(" 次，完成率 ").append(summary.percent()).append("%。\n");
        } else {
            message.append("本月已保存 ").append(summary.totalInspections).append(" 条正式检查记录。\n");
        }
        if (summary.hasMissedWeek()) {
            message.append("漏检周：");
            for (int i = 0; i < summary.missedWeeks.size(); i++) {
                if (i > 0) message.append("、");
                message.append(summary.missedWeeks.get(i).label());
            }
            message.append("。\n");
        } else {
            message.append("截至目前没有已结束自然周被判定为漏检。\n");
        }
        message.append("自然周按周一至周日计算，跨月周归属于周一所在月份；当前尚未结束的一周不会提前判定漏检。\n\n");
        for (MonthlyPlanConfig.Result result : summary.results) {
            message.append("• ").append(result.item.name).append("：已检 ")
                    .append(result.actual).append(" 次");
            if (result.item.target > 0) {
                message.append(" / 目标 ").append(result.item.target).append(" 次")
                        .append("（").append(result.percent()).append("%）");
                if (result.reached()) message.append(" ✓");
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
    }'''
replace_between(
    "app/src/main/java/cn/safetyledger/app/LedgerActivity.java",
    "    private LinearLayout monthProgressPanel() {",
    "    private String compactProgressName",
    progress_block,
)

# Artifact names in CI.
text = read(".github/workflows/android-build.yml")
if "1.2.29" in text:
    write(".github/workflows/android-build.yml", text.replace("1.2.29", "1.2.30"))
    print("updated: .github/workflows/android-build.yml")

version = ROOT / "app/VERSION_1.2.30.txt"
version.write_text(
    "安全检查台账 Android 1.2.30\n"
    "- 检查填报页标题简化为“检查填表”\n"
    "- 首页右侧恢复“本月检查进度”看板\n"
    "- 镂空圆环显示月度计划完成率\n"
    "- 目标数与已检数分色显示，达标项目显示勾号\n"
    "- 已结束自然周无匹配正式检查时显示红底白字“漏”预警\n"
    "- 自然周按周一至周日，跨月周归属于周一所在月份\n"
    "- 每月计划名称继续完全由用户新增、删除、改名和排序\n",
    encoding="utf-8",
)
print("created: app/VERSION_1.2.30.txt")
