package cn.safetyledger.app;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.Entities.Template;
import cn.safetyledger.app.data.LedgerRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * User-managed monthly inspection plan.
 *
 * Each plan item has a free-form display name, a free-form keyword matcher and an optional
 * monthly target. It is deliberately independent from template names so users can create
 * items such as "共享单车", "美团", "车棚" or any later business category without a schema change.
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

    public static final class Summary {
        public final int totalInspections;
        public final int plannedTotal;
        public final int completedAgainstPlan;
        public final List<Result> results;

        Summary(int totalInspections, int plannedTotal, int completedAgainstPlan,
                List<Result> results) {
            this.totalInspections = totalInspections;
            this.plannedTotal = plannedTotal;
            this.completedAgainstPlan = completedAgainstPlan;
            this.results = results;
        }

        public int percent() {
            return plannedTotal <= 0 ? 0
                    : Math.min(100, Math.round(completedAgainstPlan * 100f / plannedTotal));
        }
    }

    public static List<Item> load(LedgerRepository repo) {
        String raw = repo.setting(SETTING_KEY, "");
        if (raw == null || raw.isBlank()) return migrateLegacyOnce(repo);
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
            // Keep the app usable if an old or manually edited setting is malformed.
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
        List<Inspection> completedRecords = new ArrayList<>();
        for (Inspection record : records) {
            if (record == null || record.deletedAt != null || "DRAFT".equals(record.status)) continue;
            completedRecords.add(record);
        }

        List<Result> results = new ArrayList<>();
        int plannedTotal = 0;
        int completedAgainstPlan = 0;
        for (Item item : items) {
            int actual = 0;
            for (Inspection record : completedRecords) {
                if (matches(item, record)) actual++;
            }
            results.add(new Result(item.copy(), actual));
            if (item.target > 0) {
                plannedTotal += item.target;
                completedAgainstPlan += Math.min(actual, item.target);
            }
        }
        return new Summary(completedRecords.size(), plannedTotal, completedAgainstPlan, results);
    }

    /**
     * Match against the fields users most commonly use to distinguish an inspection.
     * Multiple keywords can be separated by |, Chinese comma or ordinary comma; any match counts.
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

    /**
     * One-time convenience migration. Existing 1.2.28 template-linked targets become ordinary
     * editable entries. After this save there is no permanent linkage to templates.
     */
    private static List<Item> migrateLegacyOnce(LedgerRepository repo) {
        List<Item> migrated = new ArrayList<>();
        try {
            for (Template template : repo.templates(false)) {
                String name = template.name == null || template.name.isBlank()
                        ? template.category : template.name;
                if (name == null || name.isBlank()) continue;
                int target = InspectionPlan.target(repo, template.id);
                migrated.add(new Item(UUID.randomUUID().toString(), name, name, target));
            }
        } catch (Exception ignored) {}
        if (!migrated.isEmpty()) save(repo, migrated);
        return migrated;
    }
}
