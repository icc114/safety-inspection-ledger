from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"{label}: expected source not found")
    return text.replace(old, new, 1)


# 1) Template editor: keep nine A4 slots, but allow materially longer content like the
# shared-bike template that already fits on one A4 page.
p = Path('app/src/main/java/cn/safetyledger/app/TemplateActivity.java')
t = p.read_text(encoding='utf-8')
t = t.replace('private static final int MAX_ITEM_CONTENT = 40;',
              'private static final int MAX_ITEM_CONTENT = 100;')
t = t.replace('private static final int MAX_ITEM_STANDARD = 24;',
              'private static final int MAX_ITEM_STANDARD = 60;')
t = t.replace(
    '版式限制：最多 9 个检查项目；检查内容最多 40 字，检查标准最多 24 字。这样可保证正式 PDF 第1页保留完整检查表与签名，第2页起只放检查/整改照片。',
    '版式限制：最多 9 个检查项目；检查内容最多 100 字，检查标准最多 60 字。正式 PDF 会根据单元格文字量自动调整字号，优先保证第1页检查表、整改意见、整改记录和签名完整，第2页起只放检查/整改照片。')
t = t.replace('EditText content = Ui.input(this, "检查内容（最多40字）");',
              'EditText content = Ui.input(this, "检查内容（最多100字）");')
t = t.replace('EditText standard = Ui.input(this, "检查标准（最多24字）");',
              'EditText standard = Ui.input(this, "检查标准（最多60字）");')
t = replace_once(t,
'''        category.setSingleLine(true);
        content.setSingleLine(true);
        standard.setSingleLine(true);''',
'''        category.setSingleLine(true);
        content.setSingleLine(false);
        content.setMinLines(2);
        content.setMaxLines(4);
        standard.setSingleLine(false);
        standard.setMinLines(2);
        standard.setMaxLines(3);''',
'long template inputs')
p.write_text(t, encoding='utf-8')


# 2) Android PDF: delete the redundant inspection-count summary. Keep only
# rectification opinion + rectification record, and use the released space to make the
# nine inspection rows slightly taller. Long cells shrink their own font instead of
# silently losing text at the old fixed line count.
p = Path('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java')
t = p.read_text(encoding='utf-8')
t = t.replace('final float detailHeight = 102;', 'final float detailHeight = 90;')

t = replace_once(t,
'''                cell(canvas, columns[0], y, columns[1] - columns[0], itemHeight,
                        item.category, font + .5f, maximumLines, Paint.Align.CENTER);''',
'''                fittedCell(canvas, columns[0], y, columns[1] - columns[0], itemHeight,
                        item.category, font + .5f, 7.0f, Paint.Align.CENTER);''',
'category adaptive cell')

t = replace_once(t,
'''                cell(canvas, columns[2], y, columns[3] - columns[2], itemHeight,
                        description, font, maximumLines, Paint.Align.LEFT);''',
'''                fittedCell(canvas, columns[2], y, columns[3] - columns[2], itemHeight,
                        description, font, 6.8f, Paint.Align.LEFT);''',
'description adaptive cell')

t = replace_once(t,
'''                cell(canvas, columns[4], y, columns[5] - columns[4], itemHeight,
                        item.problem, font, maximumLines, Paint.Align.LEFT);''',
'''                fittedCell(canvas, columns[4], y, columns[5] - columns[4], itemHeight,
                        item.problem, font, 6.8f, Paint.Align.LEFT);''',
'problem adaptive cell')

summary_pattern = re.compile(
    r'    private void drawInspectionSummary\(Canvas canvas, Inspection record, float y, float height\) \{.*?\n    \}\n\n    private void drawSignatures',
    re.S)
new_summary = '''    private void drawInspectionSummary(Canvas canvas, Inspection record, float y, float height) {
        rect(canvas, MARGIN, y, WIDTH - 2f * MARGIN, height);
        float split = MARGIN + 88;
        line(canvas, split, y, split, y + height);

        List<String> problems = new ArrayList<>();
        for (InspectionItem item : record.items) {
            if (!"FAIL".equals(item.result)) continue;
            String problem = item.problem == null ? "" : item.problem.trim();
            problems.add(item.order + ". " + (problem.isBlank() ? "需整改" : problem));
        }
        String opinion = problems.isEmpty() ? "无" : String.join("；", problems);
        String rectification = record.rectification == null ? "" : record.rectification.trim();
        String status = rectificationStatus(record.status);
        String rectificationText = rectification.isBlank()
                ? status : rectification + "（" + status + "）";

        float half = height / 2f;
        text(canvas, MARGIN + 6, y + 25, "整改意见：", 11, Paint.Align.LEFT, true);
        fittedWrapped(canvas, opinion, split + 6, y + 3,
                WIDTH - MARGIN - split - 12, half - 6, 9.5f, 7.0f, Paint.Align.LEFT);

        text(canvas, MARGIN + 6, y + half + 25, "整改记录：", 11, Paint.Align.LEFT, true);
        fittedWrapped(canvas, rectificationText, split + 6, y + half + 3,
                WIDTH - MARGIN - split - 12, half - 6, 9.5f, 7.0f, Paint.Align.LEFT);
    }

    private void drawSignatures'''
if '"检查情况："' in t:
    t, count = summary_pattern.subn(new_summary, t, count=1)
    if count != 1:
        raise SystemExit('summary method replacement failed')

helper_anchor = '''    private void cell(Canvas canvas, float x, float y, float width, float height,
                      String value, float size, int maximumLines, Paint.Align alignment) {
        rect(canvas, x, y, width, height);
        wrapped(canvas, value, x + 3, y + 2, width - 6, size,
                maximumLines, alignment);
    }
'''
helpers = helper_anchor + '''
    private void fittedCell(Canvas canvas, float x, float y, float width, float height,
                            String value, float maximumSize, float minimumSize,
                            Paint.Align alignment) {
        rect(canvas, x, y, width, height);
        fittedWrapped(canvas, value, x + 3, y + 2, width - 6, height - 4,
                maximumSize, minimumSize, alignment);
    }

    private void fittedWrapped(Canvas canvas, String value, float x, float y, float width,
                               float height, float maximumSize, float minimumSize,
                               Paint.Align alignment) {
        float size = maximumSize;
        int lineCount = wrappedLineCount(value, width, size);
        int maximumLines = Math.max(1, (int) (height / (size * 1.18f)));
        while (lineCount > maximumLines && size > minimumSize + .01f) {
            size = Math.max(minimumSize, size - .35f);
            lineCount = wrappedLineCount(value, width, size);
            maximumLines = Math.max(1, (int) (height / (size * 1.18f)));
        }
        wrapped(canvas, value, x, y, width, size, maximumLines, alignment);
    }

    private int wrappedLineCount(String value, float width, float size) {
        if (value == null || value.isEmpty()) return 1;
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        int lines = 0;
        for (String paragraph : value.split("\\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < paragraph.length();) {
                int codePoint = paragraph.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                if (paint.measureText(line + character) > width && line.length() > 0) {
                    lines++;
                    line.setLength(0);
                }
                line.append(character);
                offset += Character.charCount(codePoint);
            }
            lines++;
        }
        return Math.max(1, lines);
    }
'''
if 'private void fittedCell(' not in t:
    if helper_anchor not in t:
        raise SystemExit('cell helper anchor not found')
    t = t.replace(helper_anchor, helpers, 1)
p.write_text(t, encoding='utf-8')


# 3) PC Word export follows the same first-page structure: no inspection-count row,
# exact entered rectification opinions, and two larger rows for opinion/record.
p = Path('desktop/src/main/java/cn/safetyledger/pc/WordExporter.java')
t = p.read_text(encoding='utf-8')
t = t.replace('public static final int LAYOUT_VERSION = 3;',
              'public static final int LAYOUT_VERSION = 4;')
t = t.replace('int rowHeight = 10200 / layoutRows;',
              'int rowHeight = 10560 / layoutRows;')
word_summary_pattern = re.compile(
    r'    private static void addSummary\(XWPFDocument doc, ArchiveService.Record record\) \{.*?\n    \}\n\n    private static void addSignatures',
    re.S)
new_word_summary = '''    private static void addSummary(XWPFDocument doc, ArchiveService.Record record) {
        int[] widths = {1760, 9180};
        XWPFTable table = table(doc, 2, 2, widths);
        hideInsideHorizontalBorder(table);
        for (XWPFTableRow row : table.getRows()) exactRow(row, 840);

        List<String> problems = new ArrayList<>();
        if (record.items != null) {
            for (ArchiveService.Item item : record.items) {
                if (!"FAIL".equals(item.result)) continue;
                String problem = item.problem == null ? "" : item.problem.trim();
                problems.add(item.order + ". " + (problem.isBlank() ? "需整改" : problem));
            }
        }
        String opinion = problems.isEmpty() ? "无" : String.join("；", problems);
        String rectificationValue = record.rectification == null ? "" : record.rectification.trim();
        String status = rectificationStatus(record.status);
        String rectification = rectificationValue.isBlank()
                ? status : rectificationValue + "（" + status + "）";

        cell(table.getRow(0).getCell(0), "整改意见：", 11, true, ParagraphAlignment.LEFT);
        cell(table.getRow(0).getCell(1), opinion, 9, false, ParagraphAlignment.LEFT);
        cell(table.getRow(1).getCell(0), "整改记录：", 11, true, ParagraphAlignment.LEFT);
        cell(table.getRow(1).getCell(1), rectification, 9, false, ParagraphAlignment.LEFT);
    }

    private static void addSignatures'''
if '"检查情况："' in t:
    t, count = word_summary_pattern.subn(new_word_summary, t, count=1)
    if count != 1:
        raise SystemExit('Word summary replacement failed')
p.write_text(t, encoding='utf-8')


# 4) Update regression test to lock the requested layout.
p = Path('desktop/src/test/java/cn/safetyledger/pc/WordExporterTest.java')
t = p.read_text(encoding='utf-8')
t = t.replace('assertTrue(doc.getTables().get(2).getText().contains("检查情况："));\n                ',
              'assertFalse(doc.getTables().get(2).getText().contains("检查情况："));\n                ')
t = t.replace('assertTrue(doc.getTables().get(2).getText().contains("整改意见："));',
              'assertTrue(doc.getTables().get(2).getText().contains("整改意见："));\n                assertTrue(doc.getTables().get(2).getText().contains("1. 1具灭火器过期"));\n                assertTrue(doc.getTables().get(2).getText().contains("整改记录："));')
p.write_text(t, encoding='utf-8')


# 5) Version and artifact labels.
p = Path('app/build.gradle')
t = p.read_text(encoding='utf-8')
t = re.sub(r'versionCode\s+21\b', 'versionCode 22', t)
t = re.sub(r"versionName\s+'1\.2\.18'", "versionName '1.2.19'", t)
p.write_text(t, encoding='utf-8')

for workflow in ['.github/workflows/android-build.yml', '.github/workflows/android-release.yml']:
    p = Path(workflow)
    t = p.read_text(encoding='utf-8')
    t = re.sub(r'安全检查台账-1\.2\.(?:17|18)', '安全检查台账-1.2.19', t)
    p.write_text(t, encoding='utf-8')

print('Applied Android 1.2.19 form-layout changes: longer template text, no inspection-count summary, rectification-only footer.')
