package cn.safetyledger.app;

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

/**
 * User-managed monthly inspection plan and dashboard statistics.
 *
 * Plan names are entirely user-defined. Monthly target counts are independent from the
 * natural-week missed-inspection warning: the warning simply checks whether an already
 * completed Monday-Sunday week owned by the displayed month contained at least one formal
 * inspection matching any configured plan item.
 */
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

        Result(Item item, int actual) {
            this.item = item;
            this.actual = actual;
        }

        public int percent() {
            if (item.target <= 0) return 0;
            return Math.min(100, Math.round(actual * 100f / item.target));
        }

        public boolean reached() {
            return item.target > 0 && actual >= item.target;
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
        /** Actual count across items that have a target. May exceed plannedTotal. */
        public final int actualAgainstPlan;
        /** Actual count capped per item for percentage calculation. */
        public final int completedAgainstPlan;
        public final List<Result> results;
        public final List<WeekGap> missedWeeks;

        Summary(int totalInspections, int plannedTotal, int actualAgainstPlan,
                int completedAgainstPlan, List<Result> results, List<WeekGap> missedWeeks) {
            this.totalInspections = totalInspections;
            this.plannedTotal = plannedTotal;
            this.actualAgainstPlan = actualAgainstPlan;
            this.completedAgainstPlan = completedAgainstPlan;
            this.results = results;
            this.missedWeeks = missedWeeks;
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
        List<Inspection> records = repo.list(from, to, null, null, false, 1, 100000).rows;
        List<Inspection> completedRecords = formal(records);

        List<Result> results = new ArrayList<>();
        int plannedTotal = 0;
        int actualAgainstPlan = 0;
        int completedAgainstPlan = 0;
        for (Item item : items) {
            int actual = 0;
            for (Inspection record : completedRecords) {
                if (matches(item, record)) actual++;
            }
            results.add(new Result(item.copy(), actual));
            if (item.target > 0) {
                plannedTotal += item.target;
                actualAgainstPlan += actual;
                completedAgainstPlan += Math.min(actual, item.target);
            }
        }

        List<WeekGap> missedWeeks = calculateMissedWeeks(repo, month, items);
        return new Summary(completedRecords.size(), plannedTotal, actualAgainstPlan,
                completedAgainstPlan, results, missedWeeks);
    }

    private static List<WeekGap> calculateMissedWeeks(LedgerRepository repo, YearMonth month,
                                                       List<Item> items) {
        LocalDate firstMonday = firstOwnedMonday(month);
        LocalDate lastMonday = month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
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
            try { qualifyingDates.add(LocalDate.parse(record.date)); }
            catch (Exception ignored) {}
        }
        return findMissedOwnedWeeks(month, LocalDate.now(), qualifyingDates);
    }

    /**
     * A week belongs to the month containing its Monday. This is why, for August 2026,
     * Aug 1-2 belong to July's final week and August week 1 starts on Monday Aug 3.
     * The current unfinished week is never marked as missed.
     */
    static List<WeekGap> findMissedOwnedWeeks(YearMonth month, LocalDate today,
                                               List<LocalDate> qualifyingDates) {
        List<WeekGap> gaps = new ArrayList<>();
        LocalDate monday = firstOwnedMonday(month);
        LocalDate lastMonday = month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
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

    /**
     * Match against the fields most often used to distinguish an inspection. Multiple keywords
     * may be separated by |, Chinese comma or ordinary comma; any matching token counts.
     */
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
