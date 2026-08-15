from pathlib import Path

p = Path('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java')
s = p.read_text(encoding='utf-8')

old = '''        final float detailHeight = 90;
        final float signatureHeight = 78;
        final float signatureY = HEIGHT - 31 - signatureHeight;
        final float detailY = signatureY - detailHeight;
        int itemCount = Math.max(1, record.items.size());
        // Only render the inspection items actually present in this record.
        // Templates may contain up to 12 items, but unused blank rows never appear in the formal PDF.
        int layoutRows = itemCount;
        float itemArea = detailY - y;
        float itemHeight = itemArea / layoutRows;
        float font = Math.max(5.2f, Math.min(10.5f, itemHeight * .22f));
        int maximumLines = Math.max(1, (int) (itemHeight / (font * 1.2f)));

        if (record.items.isEmpty()) {
            for (int column = 0; column < columns.length - 1; column++) {
                rect(canvas, columns[column], y, columns[column + 1] - columns[column], itemHeight);
            }
            text(canvas, (columns[0] + columns[5]) / 2f, y + itemHeight / 2,
                    "当前模板没有检查项目", 11, Paint.Align.CENTER, false);
        } else {
            for (InspectionItem item : record.items) {
                fittedCell(canvas, columns[0], y, columns[1] - columns[0], itemHeight,
                        item.category, font + .5f, 7.0f, Paint.Align.CENTER);
                cell(canvas, columns[1], y, columns[2] - columns[1], itemHeight,
                        String.valueOf(item.order), font + 1, 1, Paint.Align.CENTER);
                String description = item.content;
                if (!item.standard.isBlank() && !item.standard.equals(item.content)) {
                    description += "\\n标准：" + item.standard;
                }
                fittedCell(canvas, columns[2], y, columns[3] - columns[2], itemHeight,
                        description, font, 6.8f, Paint.Align.LEFT);
                cell(canvas, columns[3], y, columns[4] - columns[3], itemHeight,
                        result(item.result), font + .5f, Math.min(2, maximumLines), Paint.Align.CENTER);
                fittedCell(canvas, columns[4], y, columns[5] - columns[4], itemHeight,
                        item.problem, font, 6.8f, Paint.Align.LEFT);
                y += itemHeight;
            }
        }

        drawInspectionSummary(canvas, record, detailY, detailHeight);
        drawSignatures(canvas, record, signatureY, signatureHeight);
    }
'''

new = '''        final float detailHeight = 90;
        final float signatureHeight = 78;
        final float signatureY = HEIGHT - 31 - signatureHeight;
        final float detailY = signatureY - detailHeight;
        final float itemArea = detailY - y;

        // Keep the overall A4 framework fixed: title/basic information, item-table outer area,
        // rectification section and signatures always stay at the same coordinates.
        // Only actual item-row heights and item fonts adapt inside the fixed item-table area.
        FormItemLayout layout = buildFormItemLayout(record.items, columns, itemArea);

        if (record.items.isEmpty()) {
            float itemHeight = itemArea;
            for (int column = 0; column < columns.length - 1; column++) {
                rect(canvas, columns[column], y, columns[column + 1] - columns[column], itemHeight);
            }
            text(canvas, (columns[0] + columns[5]) / 2f, y + itemHeight / 2,
                    "当前模板没有检查项目", 11, Paint.Align.CENTER, false);
        } else {
            for (int index = 0; index < record.items.size(); index++) {
                InspectionItem item = record.items.get(index);
                float itemHeight = layout.rowHeights[index];
                float font = layout.fontSize;
                int maximumLines = Math.max(1, (int) (itemHeight / (font * 1.18f)));

                fittedCell(canvas, columns[0], y, columns[1] - columns[0], itemHeight,
                        item.category, font + .5f, Math.max(5.0f, font - 1.5f), Paint.Align.CENTER);
                cell(canvas, columns[1], y, columns[2] - columns[1], itemHeight,
                        String.valueOf(item.order), font + 1, 1, Paint.Align.CENTER);
                String description = item.content;
                if (!item.standard.isBlank() && !item.standard.equals(item.content)) {
                    description += "\\n标准：" + item.standard;
                }
                fittedCell(canvas, columns[2], y, columns[3] - columns[2], itemHeight,
                        description, font, Math.max(4.8f, font - 2.2f), Paint.Align.LEFT);
                cell(canvas, columns[3], y, columns[4] - columns[3], itemHeight,
                        result(item.result), font + .5f, Math.min(2, maximumLines), Paint.Align.CENTER);
                fittedCell(canvas, columns[4], y, columns[5] - columns[4], itemHeight,
                        item.problem, font, Math.max(4.8f, font - 2.2f), Paint.Align.LEFT);
                y += itemHeight;
            }
        }

        drawInspectionSummary(canvas, record, detailY, detailHeight);
        drawSignatures(canvas, record, signatureY, signatureHeight);
    }

    private static final class FormItemLayout {
        final float fontSize;
        final float[] rowHeights;

        FormItemLayout(float fontSize, float[] rowHeights) {
            this.fontSize = fontSize;
            this.rowHeights = rowHeights;
        }
    }

    /**
     * Fits all actual inspection rows into the fixed first-page item-table rectangle.
     * Keep normal font first, calculate row heights from real wrapped content, and only
     * reduce the common item font if those preferred row heights no longer fit on A4.
     */
    private FormItemLayout buildFormItemLayout(List<InspectionItem> items, float[] columns,
                                                float availableHeight) {
        if (items.isEmpty()) return new FormItemLayout(10.5f, new float[]{availableHeight});

        final float preferredFont = 10.5f;
        final float minimumFont = 5.0f;
        float font = preferredFont;
        float[] heights = preferredItemRowHeights(items, columns, font);

        while (sum(heights) > availableHeight + .01f && font > minimumFont + .01f) {
            font = Math.max(minimumFont, font - .35f);
            heights = preferredItemRowHeights(items, columns, font);
        }

        float total = sum(heights);
        if (total > availableHeight + .01f) {
            // Extreme text at minimum font: compress rows proportionally; fittedCell performs
            // a final per-cell font fit so the fixed form framework still cannot overflow.
            float scale = availableHeight / total;
            for (int i = 0; i < heights.length; i++) heights[i] *= scale;
        } else {
            // Use the whole fixed table area without creating any fake blank item rows.
            float extraPerRow = (availableHeight - total) / heights.length;
            for (int i = 0; i < heights.length; i++) heights[i] += extraPerRow;
        }
        return new FormItemLayout(font, heights);
    }

    private float[] preferredItemRowHeights(List<InspectionItem> items, float[] columns,
                                             float font) {
        float[] heights = new float[items.size()];
        for (int i = 0; i < items.size(); i++) {
            InspectionItem item = items.get(i);
            String description = item.content == null ? "" : item.content;
            if (item.standard != null && !item.standard.isBlank()
                    && !item.standard.equals(item.content)) {
                description += "\\n标准：" + item.standard;
            }

            float categoryWidth = columns[1] - columns[0] - 6;
            float descriptionWidth = columns[3] - columns[2] - 6;
            float resultWidth = columns[4] - columns[3] - 6;
            float problemWidth = columns[5] - columns[4] - 6;

            int lines = 1;
            lines = Math.max(lines, wrappedLineCount(item.category, categoryWidth, font + .5f));
            lines = Math.max(lines, wrappedLineCount(description, descriptionWidth, font));
            lines = Math.max(lines, wrappedLineCount(result(item.result), resultWidth, font + .5f));
            lines = Math.max(lines, wrappedLineCount(item.problem, problemWidth, font));
            heights[i] = Math.max(25f, lines * font * 1.18f + 8f);
        }
        return heights;
    }

    private float sum(float[] values) {
        float total = 0;
        for (float value : values) total += value;
        return total;
    }
'''

if old not in s:
    raise SystemExit('Expected 1.2.21 drawForm block was not found; refusing unsafe patch')
s = s.replace(old, new)
p.write_text(s, encoding='utf-8')

b = Path('app/build.gradle')
t = b.read_text(encoding='utf-8')
if 'versionCode 24' not in t or "versionName '1.2.21'" not in t:
    raise SystemExit('Expected Android 1.2.21 version was not found')
t = t.replace('versionCode 24', 'versionCode 25')
t = t.replace("versionName '1.2.21'", "versionName '1.2.22'")
b.write_text(t, encoding='utf-8')

Path('docs/release-1.2.22.md').write_text(
    '# Android 1.2.22\n\n'
    '- A4 第1页整体框架坐标保持不变。\n'
    '- 只显示实际检查项目，不补 12 项上限的空白行。\n'
    '- 行高按照各检查项目实际文字量自动分配；文字较多的项目获得更高行。\n'
    '- 优先保持正常字号；当按内容所需行高无法装入固定 A4 检查项区域时，再逐级缩小检查项字号。\n'
    '- 整改意见、整改记录和三方签名位置固定在第1页；照片仍从第2页开始。\n',
    encoding='utf-8'
)
