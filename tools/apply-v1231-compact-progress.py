from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        if new in text:
            print(f"already updated: {path}")
            return
        raise SystemExit(f"expected text not found in {path}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))
    print(f"updated: {path}")


def replace_between(path, start, end, replacement):
    text = read(path)
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"start marker not found in {path}")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"end marker not found in {path}")
    write(path, text[:i] + replacement + "\n\n" + text[j:])
    print(f"updated block: {path}")


replace_once(
    "app/build.gradle",
    "versionCode 33\n        versionName '1.2.30'",
    "versionCode 34\n        versionName '1.2.31'",
)

monthly = r'''package cn.safetyledger.app;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.LedgerRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** User-managed monthly inspection plan and compact dashboard statistics. */
public final class MonthlyPlanConfig {
    public static final String SETTING_KEY = "monthly_plan_items_v2";
    public static final int MAX_TARGET = 999;

    private MonthlyPlanConfig() {}

    public static final class Item {
        public String id;
        public String name;
        public String keyword;
        public int target;

        public Item(String id, String name, String keyword, int target) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.keyword = keyword == null ? "" : keyword;
            this.target = Math.max(0, Math.min(MAX_TARGET, target));
        }

        public Item copy() {
            return new Item(id, name, keyword, target);
        }
    }

    public static final class Result {
        public final Item item;
        public final int actual;
        /** The immediately previous natural week owned by the displayed month had no matching record. */
        public final boolean lastCompletedWeekMissed;
        /** The current natural week owned by the displayed month already has a matching record. */
        public final boolean currentWeekHasInspection;

        Result(Item item, int actual, boolean lastCompletedWeekMissed,
               boolean currentWeekHasInspection) {
            this.item = item;
            this.actual = actual;
            this.lastCompletedWeekMissed = lastCompletedWeekMissed;
            this.currentWeekHasInspection = currentWeekHasInspection;
        }

        public int percent() {
            if (item.target <= 0) return 0;
            return Math.min(100, Math.round(actual * 100f / item.target));
        }

        public boolean reached() {
            return item.target > 0 && actual >= item.target;
        }

        /** A green check is intentionally hidden until the current week has a record. */
        public boolean shouldShowReachedBadge() {
            return reached() && !lastCompletedWeekMissed && currentWeekHasInspection;
        }
    }

    public static final class WeekGap {
        public final LocalDate monday;
        public final LocalDate sunday;

        WeekGap(LocalDate monday, LocalDate sunday) {
            this.monday = monday;
            this.sunday = sunday;
        }

        public String label() {
            return monday.getMonthValue() + "/" + monday.getDayOfMonth()
                    + "-" + sunday.getMonthValue() + "/" + sunday.getDayOfMonth();
        }
    }

    public static final class Summary {
        public final int totalInspections;
        public final int plannedTotal;
        public final int actualAgainstPlan;
        public final int completedAgainstPlan;
        public final List<Result> results;
        public final List<WeekGap> missedWeeks;
        public final boolean lastCompletedWeekMissed;
        public final boolean currentWeekHasInspection;

        Summary(int totalInspections, int plannedTotal, int actualAgainstPlan,
                int completedAgainstPlan, List<Result> results, List<WeekGap> missedWeeks,
                boolean lastCompletedWeekMissed, boolean currentWeekHasInspection) {
            this.totalInspections = totalInspections;
            this.plannedTotal = plannedTotal;
            this.actualAgainstPlan = actualAgainstPlan;
            this.completedAgainstPlan = completedAgainstPlan;
            this.results = results;
            this.missedWeeks = missedWeeks;
            this.lastCompletedWeekMissed = lastCompletedWeekMissed;
            this.currentWeekHasInspection = currentWeekHasInspection;
        }

        public int percent() {
            return plannedTotal <= 0 ? 0
                    : Math.min(100, Math.round(completedAgainstPlan * 100f / plannedTotal));
        }

        public boolean reached() {
            return plannedTotal > 0 && completedAgainstPlan >= plannedTotal;
        }

        public boolean hasMissedWeek() {
            return !missedWeeks.isEmpty();
        }

        public boolean shouldShowReachedBadge() {
            return reached() && !lastCompletedWeekMissed && currentWeekHasInspection;
        }
    }

    public static List<Item> load(LedgerRepository repo) {
        String raw = repo.setting(SETTING_KEY, "");
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        List<Item> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String id = object.optString("id", UUID.randomUUID().toString());
                String name = object.optString("name", "").trim();
                String keyword = object.optString("keyword", "").trim();
                int target = Math.max(0, Math.min(MAX_TARGET, object.optInt("target", 0)));
                if (!name.isBlank()) result.add(new Item(id, name, keyword, target));
            }
        } catch (Exception ignored) {
            // Malformed settings must not prevent the ledger from opening.
        }
        return result;
    }

    public static void save(LedgerRepository repo, List<Item> items) {
        JSONArray array = new JSONArray();
        if (items != null) {
            for (Item item : items) {
                if (item == null || item.name == null || item.name.trim().isBlank()) continue;
                JSONObject object = new JSONObject();
                try {
                    object.put("id", item.id == null || item.id.isBlank()
                            ? UUID.randomUUID().toString() : item.id);
                    object.put("name", item.name.trim());
                    object.put("keyword", item.keyword == null ? "" : item.keyword.trim());
                    object.put("target", Math.max(0, Math.min(MAX_TARGET, item.target)));
                    array.put(object);
                } catch (Exception ignored) {}
            }
        }
        repo.putSetting(SETTING_KEY, array.toString());
    }

    public static Summary summarize(LedgerRepository repo, YearMonth month) {
        List<Item> items = load(repo);
        String from = month.atDay(1).toString();
        String to = month.atEndOfMonth().toString();
        List<Inspection> completedRecords = formal(
                repo.list(from, to, null, null, false, 1, 100000).rows);

        LocalDate today = LocalDate.now();
        LocalDate currentMonday = mondayOf(today);
        LocalDate previousMonday = currentMonday.minusWeeks(1);
        List<Inspection> statusRecords = formal(repo.list(previousMonday.toString(),
                currentMonday.plusDays(6).toString(), null, null, false, 1, 100000).rows);
        boolean previousOwned = weekOwnedBy(month, previousMonday);
        boolean currentOwned = weekOwnedBy(month, currentMonday);

        List<Result> results = new ArrayList<>();
        int plannedTotal = 0;
        int actualAgainstPlan = 0;
        int completedAgainstPlan = 0;
        for (Item item : items) {
            int actual = 0;
            for (Inspection record : completedRecords) {
                if (matches(item, record)) actual++;
            }
            boolean previousMissed = previousOwned
                    && !hasMatchingInspection(statusRecords, item, previousMonday);
            boolean currentHas = currentOwned
                    && hasMatchingInspection(statusRecords, item, currentMonday);
            results.add(new Result(item.copy(), actual, previousMissed, currentHas));
            if (item.target > 0) {
                plannedTotal += item.target;
                actualAgainstPlan += actual;
                completedAgainstPlan += Math.min(actual, item.target);
            }
        }

        boolean overallPreviousMissed = previousOwned
                && !hasAnyPlanInspection(statusRecords, items, previousMonday);
        boolean overallCurrentHas = currentOwned
                && hasAnyPlanInspection(statusRecords, items, currentMonday);
        List<WeekGap> missedWeeks = calculateMissedWeeks(repo, month, items);
        return new Summary(completedRecords.size(), plannedTotal, actualAgainstPlan,
                completedAgainstPlan, results, missedWeeks,
                overallPreviousMissed, overallCurrentHas);
    }

    private static boolean hasMatchingInspection(List<Inspection> records, Item item,
                                                 LocalDate monday) {
        LocalDate sunday = monday.plusDays(6);
        for (Inspection record : records) {
            LocalDate date = recordDate(record);
            if (date == null || date.isBefore(monday) || date.isAfter(sunday)) continue;
            if (matches(item, record)) return true;
        }
        return false;
    }

    private static boolean hasAnyPlanInspection(List<Inspection> records, List<Item> items,
                                                LocalDate monday) {
        LocalDate sunday = monday.plusDays(6);
        for (Inspection record : records) {
            LocalDate date = recordDate(record);
            if (date == null || date.isBefore(monday) || date.isAfter(sunday)) continue;
            if (items == null || items.isEmpty()) return true;
            for (Item item : items) {
                if (matches(item, record)) return true;
            }
        }
        return false;
    }

    private static LocalDate recordDate(Inspection record) {
        try { return record == null || record.date == null ? null : LocalDate.parse(record.date); }
        catch (Exception ignored) { return null; }
    }

    static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static boolean weekOwnedBy(YearMonth month, LocalDate monday) {
        return month != null && monday != null && YearMonth.from(monday).equals(month);
    }

    private static List<WeekGap> calculateMissedWeeks(LedgerRepository repo, YearMonth month,
                                                       List<Item> items) {
        LocalDate firstMonday = firstOwnedMonday(month);
        LocalDate lastMonday = month.atEndOfMonth()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (firstMonday.isAfter(lastMonday)) return new ArrayList<>();

        LocalDate lastSunday = lastMonday.plusDays(6);
        List<Inspection> weeklyRecords = formal(repo.list(firstMonday.toString(), lastSunday.toString(),
                null, null, false, 1, 100000).rows);
        List<LocalDate> qualifyingDates = new ArrayList<>();
        for (Inspection record : weeklyRecords) {
            boolean qualifies = items.isEmpty();
            if (!qualifies) {
                for (Item item : items) {
                    if (matches(item, record)) { qualifies = true; break; }
                }
            }
            if (!qualifies) continue;
            LocalDate date = recordDate(record);
            if (date != null) qualifyingDates.add(date);
        }
        return findMissedOwnedWeeks(month, LocalDate.now(), qualifyingDates);
    }

    /**
     * A week belongs to the month containing its Monday. The current unfinished week is never
     * added to the historical missed-week list.
     */
    static List<WeekGap> findMissedOwnedWeeks(YearMonth month, LocalDate today,
                                               List<LocalDate> qualifyingDates) {
        List<WeekGap> gaps = new ArrayList<>();
        LocalDate monday = firstOwnedMonday(month);
        LocalDate lastMonday = month.atEndOfMonth()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Set<LocalDate> dates = new HashSet<>(qualifyingDates == null ? List.of() : qualifyingDates);
        while (!monday.isAfter(lastMonday)) {
            LocalDate sunday = monday.plusDays(6);
            if (!sunday.isBefore(today)) {
                monday = monday.plusWeeks(1);
                continue;
            }
            boolean found = false;
            for (LocalDate date : dates) {
                if (!date.isBefore(monday) && !date.isAfter(sunday)) {
                    found = true;
                    break;
                }
            }
            if (!found) gaps.add(new WeekGap(monday, sunday));
            monday = monday.plusWeeks(1);
        }
        return gaps;
    }

    static LocalDate firstOwnedMonday(YearMonth month) {
        return month.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private static List<Inspection> formal(List<Inspection> records) {
        List<Inspection> out = new ArrayList<>();
        if (records == null) return out;
        for (Inspection record : records) {
            if (record == null || record.deletedAt != null || "DRAFT".equals(record.status)) continue;
            out.add(record);
        }
        return out;
    }

    private static boolean matches(Item item, Inspection record) {
        String matcher = item.keyword == null || item.keyword.trim().isBlank()
                ? item.name : item.keyword;
        if (matcher == null || matcher.trim().isBlank()) return false;
        String haystack = join(record.templateName, record.type, record.unit,
                record.location, record.inspectee).toLowerCase(Locale.ROOT);
        String normalized = matcher.replace('，', '|').replace(',', '|');
        String[] tokens = normalized.split("\\|");
        for (String token : tokens) {
            String value = token.trim().toLowerCase(Locale.ROOT);
            if (!value.isBlank() && haystack.contains(value)) return true;
        }
        return false;
    }

    private static String join(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) out.append(value).append('\n');
        }
        return out.toString();
    }
}
'''
write("app/src/main/java/cn/safetyledger/app/MonthlyPlanConfig.java", monthly)
print("updated: MonthlyPlanConfig.java")

donut = r'''package cn.safetyledger.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Hollow progress ring used by the monthly dashboard and each plan item. */
public final class DonutProgressView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int progress;
    private int insetDp = 5;
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
        float stroke = Ui.dp(getContext(), compact ? 3 : 4);
        track.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);
        textPaint.setTextSize(Ui.dp(getContext(), compact ? 8 : 12));
        insetDp = compact ? 4 : 5;
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

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(centerText == null ? progress + "%" : centerText, cx, baseline, textPaint);
    }
}
'''
write("app/src/main/java/cn/safetyledger/app/DonutProgressView.java", donut)
print("updated: DonutProgressView.java")

replace_once(
    "app/src/main/java/cn/safetyledger/app/LedgerActivity.java",
    "import android.widget.ProgressBar;\n",
    "import android.widget.ProgressBar;\nimport android.widget.PopupWindow;\n",
)
replace_once(
    "app/src/main/java/cn/safetyledger/app/LedgerActivity.java",
    "calendarBox.addView(monthProgressPanel(), new LinearLayout.LayoutParams(Ui.dp(this, 122), ViewGroup.LayoutParams.MATCH_PARENT));",
    "calendarBox.addView(monthProgressPanel(), new LinearLayout.LayoutParams(Ui.dp(this, 104), ViewGroup.LayoutParams.MATCH_PARENT));",
)

progress_block = r'''    private LinearLayout monthProgressPanel() {
        LinearLayout panel = Ui.column(this);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
        panel.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 11));

        MonthlyPlanConfig.Summary summary = MonthlyPlanConfig.summarize(repo, month);

        LinearLayout titleRow = Ui.row(this);
        TextView title = Ui.text(this, "本月检查进度", 9, true);
        title.setPadding(0, 0, 0, 0);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        TextView info = Ui.text(this, "!", 9, true);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, 0, 0, 0);
        info.setIncludeFontPadding(false);
        info.setTextColor(Ui.BLUE_DARK);
        info.setBackground(Ui.shape(this, Color.rgb(244, 248, 255), Color.rgb(170, 193, 226), 20));
        info.setContentDescription("检查进度说明");
        info.setOnClickListener(this::showProgressHelp);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 18), 1));
        titleRow.addView(info, new LinearLayout.LayoutParams(Ui.dp(this, 17), Ui.dp(this, 17)));
        panel.addView(titleRow);

        DonutProgressView rate = new DonutProgressView(this);
        if (summary.plannedTotal > 0) {
            rate.setProgress(summary.percent());
        } else {
            rate.setProgress(0);
            rate.setCenterText(summary.totalInspections + "次");
        }
        LinearLayout.LayoutParams donutParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
        donutParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(rate, donutParams);

        LinearLayout overall = Ui.row(this);
        overall.setGravity(Gravity.CENTER_VERTICAL);
        overall.addView(progressMetric("目标 " + summary.plannedTotal, Ui.DANGER), Ui.weight(1));
        overall.addView(progressMetric("已检 " + summary.actualAgainstPlan,
                Color.rgb(38, 177, 91)), Ui.weight(1));
        TextView overallStatus = dashboardStatus(summary.lastCompletedWeekMissed,
                summary.shouldShowReachedBadge());
        if (overallStatus != null) {
            overall.addView(overallStatus,
                    new LinearLayout.LayoutParams(Ui.dp(this, 21), Ui.dp(this, 21)));
        }
        panel.addView(overall, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));

        panel.addView(Ui.divider(this));

        int shown = 0;
        for (MonthlyPlanConfig.Result result : summary.results) {
            if (shown >= 2) break;
            panel.addView(planItemProgress(result));
            shown++;
        }

        if (summary.results.isEmpty()) {
            TextView empty = Ui.text(this, "未设置计划\n点击新增", 8, false);
            empty.setPadding(0, 0, 0, 0);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Ui.MUTED);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        } else if (summary.results.size() > shown) {
            TextView more = Ui.text(this, "＋" + (summary.results.size() - shown) + "项", 8, false);
            more.setPadding(0, 0, 0, 0);
            more.setGravity(Gravity.CENTER);
            more.setTextColor(Ui.MUTED);
            panel.addView(more, new LinearLayout.LayoutParams(-1, Ui.dp(this, 13)));
        }

        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setOnClickListener(view -> showMonthProgressDetail(summary));
        return panel;
    }

    private LinearLayout planItemProgress(MonthlyPlanConfig.Result result) {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));

        LinearLayout nameRow = Ui.row(this);
        TextView name = Ui.text(this, compactProgressName(result.item.name), 8, true);
        name.setPadding(0, 0, 0, 0);
        name.setSingleLine(true);
        name.setTextColor(Ui.TEXT);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, Ui.dp(this, 15), 1));
        TextView status = dashboardStatus(result.lastCompletedWeekMissed,
                result.shouldShowReachedBadge());
        if (status != null) {
            nameRow.addView(status, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
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
        LinearLayout.LayoutParams donutParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
        donutParams.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(donut, donutParams);

        LinearLayout metrics = Ui.row(this);
        metrics.addView(progressMetric("目标 " + result.item.target, Ui.DANGER), Ui.weight(1));
        metrics.addView(progressMetric("已检 " + result.actual,
                Color.rgb(38, 177, 91)), Ui.weight(1));
        box.addView(metrics, new LinearLayout.LayoutParams(-1, Ui.dp(this, 14)));
        return box;
    }

    private TextView progressMetric(String value, int color) {
        TextView metric = Ui.text(this, value, 7, true);
        metric.setPadding(0, 0, 0, 0);
        metric.setGravity(Gravity.CENTER);
        metric.setSingleLine(true);
        metric.setIncludeFontPadding(false);
        metric.setTextColor(color);
        return metric;
    }

    private TextView dashboardStatus(boolean missedLastWeek, boolean showReached) {
        if (missedLastWeek) return statusBadge("漏", Color.rgb(218, 57, 62));
        if (showReached) return statusBadge("✓", Color.rgb(38, 177, 91));
        return null;
    }

    private TextView statusBadge(String value, int color) {
        TextView badge = Ui.text(this, value, 9, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(0, 0, 0, 0);
        badge.setIncludeFontPadding(false);
        badge.setTextColor(Color.WHITE);
        badge.setBackground(Ui.shape(this, color, Color.TRANSPARENT, 20));
        return badge;
    }

    private void showProgressHelp(View anchor) {
        TextView help = Ui.text(this,
                "红：月度目标   绿：本月已检\n✓：目标完成且本周已有检查\n漏：上周整周没有对应检查\n本周尚未检查时不提前判漏。",
                11, false);
        help.setTextColor(Ui.TEXT);
        help.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        help.setBackground(Ui.shape(this, Color.WHITE, Color.rgb(173, 191, 218), 10));
        PopupWindow popup = new PopupWindow(help, Ui.dp(this, 230),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(Ui.dp(this, 7));
        popup.showAsDropDown(anchor, -Ui.dp(this, 205), Ui.dp(this, 3));
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
        if (summary.lastCompletedWeekMissed) {
            message.append("上周状态：漏检。\n");
        } else if (!summary.currentWeekHasInspection) {
            message.append("本周状态：尚无检查记录，不提前判定漏检。\n");
        } else {
            message.append("本周状态：已有检查记录。\n");
        }
        if (summary.hasMissedWeek()) {
            message.append("历史漏检周：");
            for (int i = 0; i < summary.missedWeeks.size(); i++) {
                if (i > 0) message.append("、");
                message.append(summary.missedWeeks.get(i).label());
            }
            message.append("。\n");
        }
        message.append("自然周按周一至周日计算，跨月周归属于周一所在月份。\n\n");
        for (MonthlyPlanConfig.Result result : summary.results) {
            message.append("• ").append(result.item.name).append("：已检 ")
                    .append(result.actual).append(" 次");
            if (result.item.target > 0) {
                message.append(" / 目标 ").append(result.item.target).append(" 次")
                        .append("（").append(result.percent()).append("%）");
            } else {
                message.append("（只统计，不设目标）");
            }
            if (result.lastCompletedWeekMissed) message.append("  漏");
            else if (result.shouldShowReachedBadge()) message.append("  ✓");
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
replace_once(
    "app/src/main/java/cn/safetyledger/app/LedgerActivity.java",
    "return value.length() > 7 ? value.substring(0, 7) + \"…\" : value;",
    "return value.length() > 6 ? value.substring(0, 6) + \"…\" : value;",
)

# Keep the test focused on the dashboard rules that are easy to regress.
test = r'''package cn.safetyledger.app;

import org.junit.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MonthlyPlanWeekStatusTest {
    @Test public void august2026FirstOwnedWeekStartsOnThird() {
        assertEquals(LocalDate.of(2026, 8, 3),
                MonthlyPlanConfig.firstOwnedMonday(YearMonth.of(2026, 8)));
    }

    @Test public void crossMonthWeekBelongsToItsMondayMonth() {
        LocalDate monday = LocalDate.of(2026, 7, 27);
        assertTrue(MonthlyPlanConfig.weekOwnedBy(YearMonth.of(2026, 7), monday));
        assertFalse(MonthlyPlanConfig.weekOwnedBy(YearMonth.of(2026, 8), monday));
    }

    @Test public void reachedBadgeRequiresCurrentWeekInspection() {
        MonthlyPlanConfig.Item item = new MonthlyPlanConfig.Item("1", "测试", "测试", 4);
        MonthlyPlanConfig.Result noCurrent = new MonthlyPlanConfig.Result(item, 6, false, false);
        MonthlyPlanConfig.Result currentDone = new MonthlyPlanConfig.Result(item, 6, false, true);
        assertFalse(noCurrent.shouldShowReachedBadge());
        assertTrue(currentDone.shouldShowReachedBadge());
    }

    @Test public void missedPreviousWeekOverridesReachedBadge() {
        MonthlyPlanConfig.Item item = new MonthlyPlanConfig.Item("1", "测试", "测试", 4);
        MonthlyPlanConfig.Result missed = new MonthlyPlanConfig.Result(item, 6, true, true);
        assertTrue(missed.lastCompletedWeekMissed);
        assertFalse(missed.shouldShowReachedBadge());
    }
}
'''
write("app/src/test/java/cn/safetyledger/app/MonthlyPlanWeekStatusTest.java", test)
print("created: MonthlyPlanWeekStatusTest.java")

version = """安全检查台账 Android 1.2.31
- 本月检查进度面板缩窄，主圆环缩小
- 每个自定义计划项目增加独立小圆环
- 目标统一红色，已检统一绿色
- 目标完成且本周已有检查时显示绿色对勾
- 上一自然周整周无对应检查时显示红底白字“漏”并覆盖对勾
- 上周已完成但本周尚未检查时不显示对勾，也不提前判漏
- 标题旁增加“!”说明按钮，点击显示简短浮层解释
"""
write("app/VERSION_1.2.31.txt", version)
print("created: app/VERSION_1.2.31.txt")
