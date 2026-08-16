from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
ledger = root / 'app/src/main/java/cn/safetyledger/app/LedgerActivity.java'
text = ledger.read_text(encoding='utf-8')

# Slightly rebalance calendar/dashboard width: dashboard stays narrow but can host a 2-column 2x2 grid.
text = text.replace(
    'calendarBox.addView(monthProgressPanel(), new LinearLayout.LayoutParams(Ui.dp(this, 104), ViewGroup.LayoutParams.MATCH_PARENT));',
    'calendarBox.addView(monthProgressPanel(), new LinearLayout.LayoutParams(Ui.dp(this, 108), ViewGroup.LayoutParams.MATCH_PARENT));'
)

start = text.index('    private LinearLayout monthProgressPanel() {')
end = text.index('    private void showProgressHelp(View anchor) {')
new_block = r'''    private LinearLayout monthProgressPanel() {
        LinearLayout panel = Ui.column(this);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        panel.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 11));

        MonthlyPlanConfig.Summary summary = MonthlyPlanConfig.summarize(repo, month);

        LinearLayout titleRow = Ui.row(this);
        TextView title = Ui.text(this, "本月检查进度", 8, true);
        title.setPadding(0, 0, 0, 0);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        TextView info = Ui.text(this, "!", 8, true);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, 0, 0, 0);
        info.setIncludeFontPadding(false);
        info.setTextColor(Ui.BLUE_DARK);
        info.setBackground(Ui.shape(this, Color.rgb(244, 248, 255), Color.rgb(170, 193, 226), 20));
        info.setContentDescription("检查进度说明");
        info.setOnClickListener(this::showProgressHelp);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 17), 1));
        titleRow.addView(info, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        panel.addView(titleRow);

        DonutProgressView rate = new DonutProgressView(this);
        rate.setCompact(true);
        if (summary.plannedTotal > 0) {
            rate.setProgress(summary.percent());
        } else {
            rate.setProgress(0);
            rate.setCenterText(summary.totalInspections + "次");
        }
        LinearLayout.LayoutParams donutParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 39), Ui.dp(this, 39));
        donutParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(rate, donutParams);

        LinearLayout overall = Ui.row(this);
        overall.setGravity(Gravity.CENTER_VERTICAL);
        overall.addView(progressNumber(String.valueOf(summary.plannedTotal), Ui.DANGER, 8), Ui.weight(1));
        overall.addView(progressNumber(String.valueOf(summary.actualAgainstPlan),
                Color.rgb(38, 177, 91), 8), Ui.weight(1));
        TextView overallStatus = overallDashboardStatus(summary);
        if (overallStatus != null) {
            overall.addView(overallStatus,
                    new LinearLayout.LayoutParams(Ui.dp(this, 19), Ui.dp(this, 19)));
        }
        panel.addView(overall, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));

        panel.addView(Ui.divider(this));

        int count = Math.min(4, summary.results.size());
        if (count == 0) {
            TextView empty = Ui.text(this, "未设置计划\n点击新增", 8, false);
            empty.setPadding(0, 0, 0, 0);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Ui.MUTED);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            LinearLayout adaptive = adaptivePlanGrid(summary.results.subList(0, count));
            panel.addView(adaptive, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setOnClickListener(view -> showMonthProgressDetail(summary));
        return panel;
    }

    /**
     * Adaptive dashboard:
     * 1 item = one large tile; 2 items = two larger stacked tiles;
     * 3 items = one large tile + two compact tiles; 4 items = compact 2 x 2 grid.
     * This avoids a large empty area when the user only configured one or two plans.
     */
    private LinearLayout adaptivePlanGrid(List<MonthlyPlanConfig.Result> results) {
        LinearLayout root = Ui.column(this);
        root.setPadding(0, Ui.dp(this, 2), 0, 0);
        int count = results.size();
        if (count == 1) {
            root.addView(planItemProgress(results.get(0), false), new LinearLayout.LayoutParams(-1, 0, 1));
            return root;
        }
        if (count == 2) {
            root.addView(planItemProgress(results.get(0), false), new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(Ui.gap(this, 1));
            root.addView(planItemProgress(results.get(1), false), new LinearLayout.LayoutParams(-1, 0, 1));
            return root;
        }
        if (count == 3) {
            // First item receives the full width so the panel still feels balanced rather than leaving a blank cell.
            root.addView(planItemProgress(results.get(0), true), new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout row = Ui.row(this);
            row.addView(planItemProgress(results.get(1), true), new LinearLayout.LayoutParams(0, -1, 1));
            row.addView(Ui.horizontalGap(this, 2));
            row.addView(planItemProgress(results.get(2), true), new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            return root;
        }

        LinearLayout row1 = Ui.row(this);
        row1.addView(planItemProgress(results.get(0), true), new LinearLayout.LayoutParams(0, -1, 1));
        row1.addView(Ui.horizontalGap(this, 2));
        row1.addView(planItemProgress(results.get(1), true), new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout row2 = Ui.row(this);
        row2.addView(planItemProgress(results.get(2), true), new LinearLayout.LayoutParams(0, -1, 1));
        row2.addView(Ui.horizontalGap(this, 2));
        row2.addView(planItemProgress(results.get(3), true), new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(row1, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Ui.gap(this, 1));
        root.addView(row2, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private LinearLayout planItemProgress(MonthlyPlanConfig.Result result, boolean compact) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, compact ? 1 : 2), Ui.dp(this, 1),
                Ui.dp(this, compact ? 1 : 2), Ui.dp(this, 1));
        box.setBackground(Ui.shape(this, Color.rgb(251, 253, 255), Color.TRANSPARENT, 8));

        LinearLayout nameRow = Ui.row(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(this, compactProgressName(result.item.name, compact ? 4 : 7),
                compact ? 7 : 8, true);
        name.setPadding(0, 0, 0, 0);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setTextColor(Ui.TEXT);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, Ui.dp(this, compact ? 13 : 15), 1));
        TextView status = itemDashboardStatus(result);
        if (status != null) {
            int badge = Ui.dp(this, compact ? 16 : 18);
            nameRow.addView(status, new LinearLayout.LayoutParams(badge, badge));
        }
        box.addView(nameRow);

        DonutProgressView donut = new DonutProgressView(this);
        donut.setCompact(true);
        if (result.item.target > 0) {
            donut.setProgress(result.percent());
        } else {
            donut.setProgress(0);
            donut.setCenterText(result.actual + "次");
        }
        int donutSize = Ui.dp(this, compact ? 25 : 32);
        LinearLayout.LayoutParams donutParams = new LinearLayout.LayoutParams(donutSize, donutSize);
        donutParams.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(donut, donutParams);

        LinearLayout metrics = Ui.row(this);
        // No repeated labels here: red number = target; green number = checked count.
        metrics.addView(progressNumber(String.valueOf(result.item.target), Ui.DANGER,
                compact ? 7 : 8), Ui.weight(1));
        metrics.addView(progressNumber(String.valueOf(result.actual),
                Color.rgb(38, 177, 91), compact ? 7 : 8), Ui.weight(1));
        box.addView(metrics, new LinearLayout.LayoutParams(-1, Ui.dp(this, compact ? 12 : 14)));
        return box;
    }

    private TextView progressNumber(String value, int color, int sizeSp) {
        TextView metric = Ui.text(this, value, sizeSp, true);
        metric.setPadding(0, 0, 0, 0);
        metric.setGravity(Gravity.CENTER);
        metric.setSingleLine(true);
        metric.setIncludeFontPadding(false);
        metric.setTextColor(color);
        return metric;
    }

    /** Reaching the monthly target suppresses a stale weekly leak warning. */
    private TextView itemDashboardStatus(MonthlyPlanConfig.Result result) {
        if (result.reached()) {
            return result.currentWeekHasInspection
                    ? statusBadge("✓", Color.rgb(38, 177, 91)) : null;
        }
        if (result.lastCompletedWeekMissed) return statusBadge("漏", Color.rgb(218, 57, 62));
        return null;
    }

    private TextView overallDashboardStatus(MonthlyPlanConfig.Summary summary) {
        if (summary.reached()) {
            return summary.currentWeekHasInspection
                    ? statusBadge("✓", Color.rgb(38, 177, 91)) : null;
        }
        if (summary.lastCompletedWeekMissed) return statusBadge("漏", Color.rgb(218, 57, 62));
        return null;
    }

    private TextView statusBadge(String value, int color) {
        TextView badge = Ui.text(this, value, 8, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(0, 0, 0, 0);
        badge.setIncludeFontPadding(false);
        badge.setTextColor(Color.WHITE);
        badge.setBackground(Ui.shape(this, color, Color.TRANSPARENT, 20));
        return badge;
    }

'''
text = text[:start] + new_block + text[end:]

# Update the help wording to match the compact numeric legend and completed-target leak suppression.
text = text.replace(
    '"红：月度目标   绿：本月已检\\n✓：目标完成且本周已有检查\\n漏：上周整周没有对应检查\\n本周尚未检查时不提前判漏。",',
    '"红色数字：月度目标   绿色数字：本月已检\\n✓：目标完成且本周已有检查\\n漏：目标未完成且上周整周没有对应检查\\n目标已完成后不再提示漏检；本周未结束不提前判漏。",'
)

# Detail dialog: do not report a leak for a plan that has already reached its monthly target.
text = text.replace(
    '            if (result.lastCompletedWeekMissed) message.append("  漏");\n            else if (result.shouldShowReachedBadge()) message.append("  ✓");',
    '            if (result.reached()) {\n                if (result.currentWeekHasInspection) message.append("  ✓");\n            } else if (result.lastCompletedWeekMissed) {\n                message.append("  漏");\n            }'
)

# Replace compact name helper with a parameterized form.
text = re.sub(
    r'    private String compactProgressName\(String name\) \{.*?\n    \}',
    '''    private String compactProgressName(String name, int maxLength) {\n        if (name == null || name.isBlank()) return "未命名";\n        String value = name.trim();\n        int limit = Math.max(2, maxLength);\n        return value.length() > limit ? value.substring(0, limit) + "…" : value;\n    }''',
    text,
    count=1,
    flags=re.S,
)

ledger.write_text(text, encoding='utf-8')

# Monthly plan editor: cap the dashboard-configurable items at four, per product requirement.
activity = root / 'app/src/main/java/cn/safetyledger/app/MonthlyPlanActivity.java'
a = activity.read_text(encoding='utf-8')
a = a.replace(
    'public final class MonthlyPlanActivity extends Activity {\n    private LedgerRepository repo;',
    'public final class MonthlyPlanActivity extends Activity {\n    private static final int MAX_PLAN_ITEMS = 4;\n    private LedgerRepository repo;'
)
a = a.replace(
    '                "名称完全由你自己填写，例如“共享单车”“美团”“车棚”，可新增、删除、改名和调整顺序。统计关键词用于从当月检查记录中识别该项目；APP 会在模板名称、检查类型、被检查单位、地点和被检查人中查找关键词，多个关键词可用 | 分隔。\\n\\n计划次数填 0 表示只统计实际检查次数；填 1、4、10 等则同时显示目标、已检次数和完成率。每保存一条正式检查记录计 1 次，草稿不计入。首页的红色“漏”是独立的自然周提醒：周一至周日为一周，跨月周归属于周一所在月份，本周未结束前不会判定漏检。",',
    '                "最多设置 4 个计划项目，名称完全由你自己填写，例如“共享单车”“美团”“车棚”，可新增、删除、改名和调整顺序。统计关键词用于从当月检查记录中识别该项目；APP 会在模板名称、检查类型、被检查单位、地点和被检查人中查找关键词，多个关键词可用 | 分隔。\\n\\n计划次数填 0 表示只统计实际检查次数；填 1、4、10 等则同时显示目标、已检次数和完成率。每保存一条正式检查记录计 1 次，草稿不计入。首页会根据计划项目数量自动排版：1-2 项放大显示，3-4 项自动紧凑排列。",'
)
a = a.replace(
    '        add.setOnClickListener(view -> {\n            draft.add(new MonthlyPlanConfig.Item(UUID.randomUUID().toString(), "", "", 1));\n            renderList();\n        });',
    '        add.setOnClickListener(view -> {\n            if (draft.size() >= MAX_PLAN_ITEMS) {\n                Ui.toast(this, "最多设置 4 个每月检查计划项目");\n                return;\n            }\n            draft.add(new MonthlyPlanConfig.Item(UUID.randomUUID().toString(), "", "", 1));\n            renderList();\n        });'
)
a = a.replace(
    '    private void savePlans() {\n        for (int i = 0; i < draft.size(); i++) {',
    '    private void savePlans() {\n        if (draft.size() > MAX_PLAN_ITEMS) {\n            Ui.toast(this, "每月检查计划最多保留 4 个项目，请先删除多余项目");\n            return;\n        }\n        for (int i = 0; i < draft.size(); i++) {'
)
activity.write_text(a, encoding='utf-8')

# Version bump.
gradle = root / 'app/build.gradle'
g = gradle.read_text(encoding='utf-8')
g = g.replace("versionCode 34", "versionCode 35")
g = g.replace("versionName '1.2.31'", "versionName '1.2.32'")
gradle.write_text(g, encoding='utf-8')

# Build artifact names.
workflow = root / '.github/workflows/android-build.yml'
w = workflow.read_text(encoding='utf-8')
w = w.replace('1.2.31', '1.2.32')
workflow.write_text(w, encoding='utf-8')

version = root / 'app/VERSION_1.2.32.txt'
version.write_text('''安全检查台账 Android 1.2.32\n- 本月检查进度支持最多4个自定义分项\n- 1-2个分项自动放大，3-4个分项自动紧凑/双列排版\n- 目标数字红色、已检查数字绿色，不重复显示文字标签\n- 月度目标已完成后不再显示历史漏检徽标\n- 保留总进度圆环及分项圆环\n''', encoding='utf-8')

print('Applied Android 1.2.32 adaptive progress dashboard changes.')
