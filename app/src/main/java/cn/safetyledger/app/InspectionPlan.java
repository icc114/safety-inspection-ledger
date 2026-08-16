package cn.safetyledger.app;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.Entities.Template;
import cn.safetyledger.app.data.LedgerRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Monthly inspection targets shared by Settings and the ledger calendar. */
public final class InspectionPlan {
    public static final int DEFAULT_MONTHLY_TARGET = 4;
    private static final int MAX_MONTHLY_TARGET = 31;

    private InspectionPlan() {}

    public static String settingKey(String templateId) {
        return "monthly_plan_" + templateId;
    }

    public static int target(LedgerRepository repo, String templateId) {
        String raw = repo.setting(settingKey(templateId), String.valueOf(DEFAULT_MONTHLY_TARGET));
        try {
            return Math.max(0, Math.min(MAX_MONTHLY_TARGET, Integer.parseInt(raw.trim())));
        } catch (Exception ignored) {
            return DEFAULT_MONTHLY_TARGET;
        }
    }

    public static void saveTarget(LedgerRepository repo, String templateId, int target) {
        repo.putSetting(settingKey(templateId),
                String.valueOf(Math.max(0, Math.min(MAX_MONTHLY_TARGET, target))));
    }

    public static List<Entry> entries(LedgerRepository repo, YearMonth month) {
        String start = month.atDay(1).toString();
        String end = month.atEndOfMonth().toString();
        List<Inspection> inspections = repo.list(start, end, null, null, false, 1, 100000).rows;
        Map<String, Set<String>> completedWeeks = new HashMap<>();
        for (Inspection inspection : inspections) {
            if (inspection == null || inspection.templateId == null || inspection.deletedAt != null
                    || "DRAFT".equals(inspection.status)) continue;
            try {
                LocalDate date = LocalDate.parse(inspection.date);
                int weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
                int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                completedWeeks.computeIfAbsent(inspection.templateId, ignored -> new HashSet<>())
                        .add(weekYear + "-" + week);
            } catch (Exception ignored) {}
        }

        List<Entry> result = new ArrayList<>();
        for (Template template : repo.templates(true)) {
            int planned = target(repo, template.id);
            Set<String> weeks = completedWeeks.get(template.id);
            int rawCompleted = weeks == null ? 0 : weeks.size();
            int completed = planned <= 0 ? 0 : Math.min(planned, rawCompleted);
            String name = template.name == null || template.name.isBlank()
                    ? template.category : template.name;
            result.add(new Entry(template.id, name, planned, completed));
        }
        return result;
    }

    public static final class Entry {
        public final String templateId;
        public final String name;
        public final int planned;
        public final int completed;

        Entry(String templateId, String name, int planned, int completed) {
            this.templateId = templateId;
            this.name = name;
            this.planned = planned;
            this.completed = completed;
        }

        public int percent() {
            return planned <= 0 ? 0 : Math.round(completed * 100f / planned);
        }
    }
}
