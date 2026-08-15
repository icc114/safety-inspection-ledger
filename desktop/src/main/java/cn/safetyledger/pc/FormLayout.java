package cn.safetyledger.pc;

import java.util.ArrayList;
import java.util.List;

/** Shared first-page layout calculation for Word and PDF output. */
public final class FormLayout {
    public static final int ITEM_BODY_TWIPS = 9000;
    public static final int ITEM_HEADER_TWIPS = 620;
    public static final int SUMMARY_ROW_TWIPS = 800;
    public static final int SIGNATURE_ROW_TWIPS = 700;
    public static final int MAX_ITEM_FONT = 10;
    public static final int MIN_ITEM_FONT = 6;

    private FormLayout() {}

    public static Result calculate(ArchiveService.Record record) {
        List<ArchiveService.Item> items = record == null || record.items == null
                ? List.of() : record.items;
        if (items.isEmpty()) return new Result(10, new int[0]);

        int chosen = MIN_ITEM_FONT;
        int[] required = null;
        int[] weights = null;
        for (int font = MAX_ITEM_FONT; font >= MIN_ITEM_FONT; font--) {
            int[] current = new int[items.size()];
            int[] currentWeights = new int[items.size()];
            int total = 0;
            for (int i = 0; i < items.size(); i++) {
                Measure m = measure(items.get(i), font);
                current[i] = m.requiredTwips;
                currentWeights[i] = m.weight;
                total += current[i];
            }
            chosen = font;
            required = current;
            weights = currentWeights;
            if (total <= ITEM_BODY_TWIPS) break;
        }

        int[] heights = required.clone();
        int total = sum(heights);
        if (total < ITEM_BODY_TWIPS) {
            distributeExtra(heights, weights, ITEM_BODY_TWIPS - total);
        } else if (total > ITEM_BODY_TWIPS) {
            shrinkToFit(heights, ITEM_BODY_TWIPS);
        }
        normalize(heights, ITEM_BODY_TWIPS);
        return new Result(chosen, heights);
    }

    private static Measure measure(ArchiveService.Item item, int font) {
        String content = blank(item.content);
        if (!blank(item.standard).isBlank() && !blank(item.standard).equals(content)) {
            content += "\n标准：" + item.standard;
        }
        int categoryLines = estimateLines(blank(item.category), scaledCapacity(9, font));
        int contentLines = estimateLines(content, scaledCapacity(31, font));
        int problemLines = estimateLines(blank(item.problem), scaledCapacity(17, font));
        int lines = Math.max(1, Math.max(categoryLines, Math.max(contentLines, problemLines)));
        int lineHeight = font * 25 + 18;
        int required = Math.max(360, 110 + lines * lineHeight);
        int weight = Math.max(1, lines);
        return new Measure(required, weight);
    }

    private static int scaledCapacity(int baseAtTenPt, int font) {
        return Math.max(4, (int) Math.floor(baseAtTenPt * (10.0 / Math.max(1, font))));
    }

    static int estimateLines(String value, int charsPerLine) {
        if (value == null || value.isBlank()) return 1;
        int total = 0;
        for (String line : value.split("\\n", -1)) {
            int units = visualUnits(line);
            total += Math.max(1, (units + charsPerLine - 1) / charsPerLine);
        }
        return Math.max(1, total);
    }

    private static int visualUnits(String value) {
        if (value == null || value.isEmpty()) return 0;
        int units = 0;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) units += 1;
            else if (cp <= 0x7f) units += 1;
            else units += 2;
        }
        return Math.max(1, (units + 1) / 2);
    }

    private static void distributeExtra(int[] heights, int[] weights, int extra) {
        int weightSum = Math.max(1, sum(weights));
        int used = 0;
        for (int i = 0; i < heights.length; i++) {
            int add = i == heights.length - 1 ? extra - used
                    : (int) Math.floor(extra * (weights[i] / (double) weightSum));
            heights[i] += Math.max(0, add);
            used += Math.max(0, add);
        }
    }

    private static void shrinkToFit(int[] heights, int target) {
        if (heights.length == 0) return;
        int min = Math.max(220, target / Math.max(1, heights.length * 4));
        int total = sum(heights);
        double ratio = target / (double) Math.max(1, total);
        for (int i = 0; i < heights.length; i++) {
            heights[i] = Math.max(min, (int) Math.floor(heights[i] * ratio));
        }
        while (sum(heights) > target) {
            int index = largestReducible(heights, min);
            if (index < 0) break;
            heights[index]--;
        }
    }

    private static int largestReducible(int[] values, int min) {
        int index = -1;
        int largest = Integer.MIN_VALUE;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > min && values[i] > largest) {
                largest = values[i];
                index = i;
            }
        }
        return index;
    }

    private static void normalize(int[] values, int target) {
        if (values.length == 0) return;
        int diff = target - sum(values);
        int cursor = 0;
        while (diff != 0) {
            int i = cursor++ % values.length;
            if (diff > 0) { values[i]++; diff--; }
            else if (values[i] > 180) { values[i]--; diff++; }
            if (cursor > 1_000_000) break;
        }
    }

    private static int sum(int[] values) {
        int total = 0;
        if (values != null) for (int v : values) total += v;
        return total;
    }

    private static String blank(String value) { return value == null ? "" : value.trim(); }

    private record Measure(int requiredTwips, int weight) {}

    public static final class Result {
        public final int itemFontSize;
        public final int[] rowHeightsTwips;
        Result(int itemFontSize, int[] rowHeightsTwips) {
            this.itemFontSize = itemFontSize;
            this.rowHeightsTwips = rowHeightsTwips;
        }
        public int totalItemHeight() { return sum(rowHeightsTwips); }
    }
}
