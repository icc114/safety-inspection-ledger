from pathlib import Path

root = Path(__file__).resolve().parents[1]

ledger = root / 'app/src/main/java/cn/safetyledger/app/LedgerActivity.java'
text = ledger.read_text(encoding='utf-8')

# Keep the dashboard narrow while leaving enough room for a clean 2-column layout.
text = text.replace(
    'calendarBox.addView(monthProgressPanel(), new LinearLayout.LayoutParams(Ui.dp(this, 108), ViewGroup.LayoutParams.MATCH_PARENT));',
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
        titleRow.addView(info, new LinearLayout.LayoutParams(Ui.dp(this, 15), Ui.dp(this, 15)));
        panel.addView(titleRow);

        SegmentedProgressView totalRing = new SegmentedProgressView(this);
        totalRing.setOverallPercent(summary.percent());
        int segmentCount = Math.min(4, summary.results.size());
        int[] segmentPercents = new int[segmentCount];
        for (int i = 0; i < segmentCount; i++) segmentPercents[i] = summary.results.get(i).percent();
        totalRing.setSegmentPercents(segmentPercents);
        LinearLayout.LayoutParams totalRingParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 43), Ui.dp(this, 43));
        totalRingParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(totalRing, totalRingParams);

        LinearLayout overall = Ui.row(this);
        overall.setGravity(Gravity.CENTER_VERTICAL);
        overall.addView(progressMetricWithLabel("目标", String.valueOf(summary.plannedTotal), Ui.DANGER), Ui.weight(1));
        overall.addView(progressMetricWithLabel("已检", String.valueOf(summary.actualAgainstPlan),
                Color.rgb(38, 177, 91)), Ui.weight(1));
        TextView overallStatus = overallDashboardStatus(summary);
        if (overallStatus != null) {
            overall.addView(overallStatus,
                    new LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)));
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
            panel.addView(adaptivePlanGrid(summary.results.subList(0, count)),
                    new LinearLayout.LayoutParams(-1, 0, 1));
        }

        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setOnClickListener(view -> showMonthProgressDetail(summary));
        return panel;
    }

    /**
     * Adaptive layout: one item is centered and enlarged; two items share the height equally;
     * three items use an equal-size 2+1 layout with the last tile centered; four items use 2x2.
     */
    private LinearLayout adaptivePlanGrid(List<MonthlyPlanConfig.Result> results) {
        LinearLayout root = Ui.column(this);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(0, Ui.dp(this, 1), 0, 0);
        int count = results.size();
        if (count == 1) {
            root.addView(planItemProgress(results.get(0), false),
                    new LinearLayout.LayoutParams(-1, 0, 1));
            return root;
        }
        if (count == 2) {
            root.addView(planItemProgress(results.get(0), false),
                    new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(Ui.gap(this, 1));
            root.addView(planItemProgress(results.get(1), false),
                    new LinearLayout.LayoutParams(-1, 0, 1));
            return root;
        }

        LinearLayout firstRow = Ui.row(this);
        firstRow.addView(planItemProgress(results.get(0), true),
                new LinearLayout.LayoutParams(0, -1, 1));
        firstRow.addView(Ui.horizontalGap(this, 2));
        firstRow.addView(planItemProgress(results.get(1), true),
                new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(firstRow, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Ui.gap(this, 1));

        LinearLayout secondRow = Ui.row(this);
        if (count == 3) {
            // Equal-size third tile, centered in the second row.
            View leftSpacer = new View(this);
            View rightSpacer = new View(this);
            secondRow.addView(leftSpacer, new LinearLayout.LayoutParams(0, -1, 1));
            secondRow.addView(planItemProgress(results.get(2), true),
                    new LinearLayout.LayoutParams(0, -1, 2));
            secondRow.addView(rightSpacer, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            secondRow.addView(planItemProgress(results.get(2), true),
                    new LinearLayout.LayoutParams(0, -1, 1));
            secondRow.addView(Ui.horizontalGap(this, 2));
            secondRow.addView(planItemProgress(results.get(3), true),
                    new LinearLayout.LayoutParams(0, -1, 1));
        }
        root.addView(secondRow, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private LinearLayout planItemProgress(MonthlyPlanConfig.Result result, boolean compact) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 1), Ui.dp(this, 1), Ui.dp(this, 1), Ui.dp(this, 1));
        box.setBackground(Ui.shape(this, Color.rgb(251, 253, 255), Color.TRANSPARENT, 7));

        // Title is always centered immediately above its own ring.
        TextView name = Ui.text(this, compactProgressName(result.item.name, compact ? 4 : 7),
                compact ? 6.8f : 8f, true);
        name.setPadding(0, 0, 0, 0);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        name.setIncludeFontPadding(false);
        name.setTextColor(Ui.TEXT);
        box.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(this, compact ? 12 : 14)));

        int badgeSize = Ui.dp(this, compact ? 12 : 13);
        int donutSize = Ui.dp(this, compact ? 27 : 34);
        LinearLayout ringRow = Ui.row(this);
        ringRow.setGravity(Gravity.CENTER);
        View badgeSpacer = new View(this);
        ringRow.addView(badgeSpacer, new LinearLayout.LayoutParams(badgeSize, badgeSize));
        DonutProgressView donut = new DonutProgressView(this);
        donut.setCompact(true);
        if (result.item.target > 0) {
            donut.setProgress(result.percent());
        } else {
            donut.setProgress(0);
            donut.setCenterText(result.actual + "次");
        }
        ringRow.addView(donut, new LinearLayout.LayoutParams(donutSize, donutSize));
        TextView status = itemDashboardStatus(result);
        if (status == null) {
            ringRow.addView(new View(this), new LinearLayout.LayoutParams(badgeSize, badgeSize));
        } else {
            ringRow.addView(status, new LinearLayout.LayoutParams(badgeSize, badgeSize));
        }
        box.addView(ringRow, new LinearLayout.LayoutParams(-1, donutSize));

        LinearLayout metrics = Ui.row(this);
        // Sub-items intentionally use numbers only: red = target, green = checked.
        metrics.addView(progressNumber(String.valueOf(result.item.target), Ui.DANGER,
                compact ? 6.8f : 8f), Ui.weight(1));
        metrics.addView(progressNumber(String.valueOf(result.actual),
                Color.rgb(38, 177, 91), compact ? 6.8f : 8f), Ui.weight(1));
        box.addView(metrics, new LinearLayout.LayoutParams(-1, Ui.dp(this, compact ? 11 : 13)));
        return box;
    }

    private TextView progressNumber(String value, int color, float sizeSp) {
        TextView metric = Ui.text(this, value, sizeSp, true);
        metric.setPadding(0, 0, 0, 0);
        metric.setGravity(Gravity.CENTER);
        metric.setSingleLine(true);
        metric.setIncludeFontPadding(false);
        metric.setTextColor(color);
        return metric;
    }

    private LinearLayout progressMetricWithLabel(String label, String value, int color) {
        LinearLayout metric = Ui.row(this);
        metric.setGravity(Gravity.CENTER);
        TextView labelView = Ui.text(this, label, 6.5f, false);
        labelView.setPadding(0, 0, 0, 0);
        labelView.setIncludeFontPadding(false);
        labelView.setTextColor(Ui.MUTED);
        TextView number = Ui.text(this, value, 7.5f, true);
        number.setPadding(Ui.dp(this, 1), 0, 0, 0);
        number.setIncludeFontPadding(false);
        number.setTextColor(color);
        metric.addView(labelView);
        metric.addView(number);
        return metric;
    }

    /** Reaching the monthly target suppresses an old weekly leak warning. */
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
        TextView badge = Ui.text(this, value, 6.8f, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(0, 0, 0, 0);
        badge.setIncludeFontPadding(false);
        badge.setTextColor(Color.WHITE);
        badge.setBackground(Ui.shape(this, color, Color.TRANSPARENT, 20));
        return badge;
    }

'''
text = text[:start] + new_block + text[end:]

text = text.replace(
    '"红色数字：月度目标   绿色数字：本月已检\\n✓：目标完成且本周已有检查\\n漏：目标未完成且上周整周没有对应检查\\n目标已完成后不再提示漏检；本周未结束不提前判漏。",',
    '"总图：目标/已检保留文字；C形彩色段对应各计划项目。\\n分项：红色数字=目标，绿色数字=本月已检。\\n✓：目标完成且本周已有检查；漏：目标未完成且上周整周无对应检查。\\n目标完成后不再提示漏检，本周未结束不提前判漏。",'
)
ledger.write_text(text, encoding='utf-8')

# Make small donut text automatically fit, so 100% is never clipped.
donut = root / 'app/src/main/java/cn/safetyledger/app/DonutProgressView.java'
donut.write_text(r'''package cn.safetyledger.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Hollow progress ring used by each monthly plan item. */
public final class DonutProgressView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int progress;
    private int insetDp = 5;
    private boolean compact;
    private String centerText;

    public DonutProgressView(Context context) {
        super(context);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Color.rgb(226, 233, 244));
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(Ui.BLUE);
        textPaint.setColor(Ui.BLUE_DARK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setCompact(false);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setCompact(boolean compact) {
        this.compact = compact;
        float stroke = Ui.dp(getContext(), compact ? 2.5f : 4f);
        track.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);
        insetDp = compact ? 3 : 5;
        invalidate();
    }

    public void setCenterText(String value) {
        centerText = value;
        invalidate();
    }

    public void setProgress(int value) {
        progress = Math.max(0, Math.min(100, value));
        setContentDescription("完成率 " + progress + "%");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = Ui.dp(getContext(), insetDp);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.max(0f, Math.min(getWidth(), getHeight()) / 2f - pad);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(arc, 0, 360, false, track);
        if (progress > 0) canvas.drawArc(arc, -90, progress * 3.6f, false, progressPaint);

        String label = centerText == null ? progress + "%" : centerText;
        float inner = Math.max(1f, radius * 2f - progressPaint.getStrokeWidth() - Ui.dp(getContext(), 2));
        float maxWidth = inner * 0.88f;
        float size = Ui.dp(getContext(), compact ? 7.2f : 12f);
        textPaint.setTextSize(size);
        float width = textPaint.measureText(label);
        if (width > maxWidth && width > 0f) {
            textPaint.setTextSize(Math.max(Ui.dp(getContext(), 4.8f), size * maxWidth / width));
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, cx, baseline, textPaint);
    }
}
''', encoding='utf-8')

# Overall C-shaped multi-colour progress view. Up to four custom plan items map to four colours.
segmented = root / 'app/src/main/java/cn/safetyledger/app/SegmentedProgressView.java'
segmented.write_text(r'''package cn.safetyledger.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** C-shaped overall progress ring whose coloured sections correspond to plan items. */
public final class SegmentedProgressView extends View {
    private static final int[] COLORS = {
            Color.rgb(36, 103, 222),
            Color.rgb(38, 177, 91),
            Color.rgb(243, 156, 45),
            Color.rgb(132, 92, 210)
    };
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segment = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int overallPercent;
    private int[] segmentPercents = new int[0];

    public SegmentedProgressView(Context context) {
        super(context);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(Ui.dp(context, 3.2f));
        track.setColor(Color.rgb(226, 233, 244));
        segment.setStyle(Paint.Style.STROKE);
        segment.setStrokeCap(Paint.Cap.ROUND);
        segment.setStrokeWidth(Ui.dp(context, 3.2f));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Ui.BLUE_DARK);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setOverallPercent(int value) {
        overallPercent = Math.max(0, Math.min(100, value));
        invalidate();
    }

    public void setSegmentPercents(int[] values) {
        if (values == null) {
            segmentPercents = new int[0];
        } else {
            int count = Math.min(4, values.length);
            segmentPercents = new int[count];
            for (int i = 0; i < count; i++) segmentPercents[i] = Math.max(0, Math.min(100, values[i]));
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float pad = Ui.dp(getContext(), 4f);
        float radius = Math.max(0f, Math.min(getWidth(), getHeight()) / 2f - pad);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 280 degrees leaves a clean opening at the right, visually reading as a C.
        final float start = 40f;
        final float totalSweep = 280f;
        canvas.drawArc(arc, start, totalSweep, false, track);

        int count = segmentPercents.length;
        if (count > 0) {
            float slot = totalSweep / count;
            float gap = count == 1 ? 0f : 5f;
            for (int i = 0; i < count; i++) {
                float available = Math.max(0f, slot - gap);
                float sweep = available * segmentPercents[i] / 100f;
                if (sweep <= 0f) continue;
                segment.setColor(COLORS[i % COLORS.length]);
                canvas.drawArc(arc, start + i * slot + gap / 2f, sweep, false, segment);
            }
        }

        String label = overallPercent + "%";
        float inner = Math.max(1f, radius * 2f - segment.getStrokeWidth() - Ui.dp(getContext(), 2));
        float size = Ui.dp(getContext(), 9.2f);
        text.setTextSize(size);
        float width = text.measureText(label);
        float maxWidth = inner * 0.82f;
        if (width > maxWidth && width > 0f) text.setTextSize(Math.max(Ui.dp(getContext(), 6f), size * maxWidth / width));
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, cx, baseline, text);
    }
}
''', encoding='utf-8')

# Plan editor wording should use the same names as the actual inspection templates rather than arbitrary brands.
activity = root / 'app/src/main/java/cn/safetyledger/app/MonthlyPlanActivity.java'
a = activity.read_text(encoding='utf-8')
a = a.replace(
    '例如“共享单车”“美团”“车棚”',
    '例如现有检查模板中的“车棚安全检查”“共享单车”'
)
a = a.replace('例如：共享单车 / 美团 / 车棚', '例如：车棚安全检查 / 共享单车')
a = a.replace('留空则使用显示名称；多个关键词用 | 分隔', '例如：车棚安全检查；多个关键词用 | 分隔')
activity.write_text(a, encoding='utf-8')

# Version bump.
gradle = root / 'app/build.gradle'
g = gradle.read_text(encoding='utf-8')
g = g.replace('versionCode 35', 'versionCode 36')
g = g.replace("versionName '1.2.32'", "versionName '1.2.33'")
gradle.write_text(g, encoding='utf-8')

version = root / 'app/VERSION_1.2.33.txt'
version.write_text('''安全检查台账 Android 1.2.33
- 本月总进度改为多色 C 形图，中间百分比自动适配不裁切
- 总进度保留“目标/已检”，分项只显示红色目标数字和绿色已检数字
- 分项标题固定显示在各自饼状图正上方
- 1项居中放大、2项等比例上下居中、3项双排且末项居中、4项2x2双排
- “漏”和绿色对勾等比例缩小并统一居中
- 月度目标完成后不再显示历史漏检提示
- 每月检查计划示例文字与现有检查模板名称保持一致
''', encoding='utf-8')

marker = root / 'tools/README-v1233-temp.txt'
if marker.exists(): marker.unlink()

print('Applied Android 1.2.33 final progress dashboard layout.')
