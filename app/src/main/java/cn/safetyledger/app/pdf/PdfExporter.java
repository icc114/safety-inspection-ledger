package cn.safetyledger.app.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.Entities.InspectionItem;
import cn.safetyledger.app.data.Entities.Media;
import cn.safetyledger.app.data.Entities.Signature;
import cn.safetyledger.app.data.LedgerRepository;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Produces a formal A4 inspection form. Every record table occupies one first page;
 * photo attachments always start from that record's second page. */
public final class PdfExporter {
    private static final int WIDTH = 595;
    private static final int HEIGHT = 842;
    private static final int MARGIN = 24;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LedgerRepository repo;

    public PdfExporter(Context context) {
        repo = new LedgerRepository(context);
        paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
    }

    private static final class PageSpec {
        final Inspection record;
        final boolean table;
        final int photoStart;

        PageSpec(Inspection record, boolean table, int photoStart) {
            this.record = record;
            this.table = table;
            this.photoStart = photoStart;
        }
    }

    public void export(List<Inspection> records, OutputStream output) throws IOException {
        validateAssets(records);
        List<PageSpec> pages = new ArrayList<>();
        for (Inspection record : records) {
            // Exactly one dynamically-sized form page, regardless of template item count.
            pages.add(new PageSpec(record, true, 0));
            // Photo attachments are deliberately added after the form, beginning on page two.
            for (int start = 0; start < record.media.size(); start += 4) {
                pages.add(new PageSpec(record, false, start));
            }
        }

        Map<String, Integer> totalsByDate = new HashMap<>();
        for (PageSpec page : pages) {
            totalsByDate.put(page.record.date,
                    totalsByDate.getOrDefault(page.record.date, 0) + 1);
        }
        Map<String, Integer> pageByDate = new HashMap<>();
        PdfDocument document = new PdfDocument();
        try {
            for (int i = 0; i < pages.size(); i++) {
                PageSpec spec = pages.get(i);
                int number = pageByDate.getOrDefault(spec.record.date, 0) + 1;
                pageByDate.put(spec.record.date, number);
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
                        WIDTH, HEIGHT, i + 1).create());
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                if (spec.table) drawForm(canvas, spec.record);
                else drawPhotos(canvas, spec.record, spec.photoStart);
                text(canvas, WIDTH / 2f, HEIGHT - 13,
                        spec.record.date + "  第" + number + "页/共"
                                + totalsByDate.get(spec.record.date) + "页",
                        9, Paint.Align.CENTER, false);
                document.finishPage(page);
            }
            document.writeTo(output);
        } finally {
            document.close();
        }
    }

    private void validateAssets(List<Inspection> records) throws IOException {
        for (Inspection record : records) {
            for (Media media : record.media) {
                if (media.localPath == null || media.localPath.isBlank()
                        || !new File(media.localPath).isFile()) {
                    throw new IOException("记录 " + record.date + " 缺少照片文件；已停止导出，避免生成不完整 PDF");
                }
            }
            for (Signature signature : repo.signatures(record.id)) {
                if (signature.path == null || !new File(signature.path).isFile()) {
                    throw new IOException("记录 " + record.date + " 缺少签名文件，已停止导出");
                }
            }
        }
    }

    private void drawForm(Canvas canvas, Inspection record) {
        float y = 29;
        text(canvas, WIDTH / 2f, y, formTitle(record), 25, Paint.Align.CENTER, true);
        y = 45;

        // Matches the source form: only inspection date/time and location precede the item table.
        float[] basic = {MARGIN, 119, 294, 394, WIDTH - MARGIN};
        row(canvas, y, 42, basic,
                new String[]{"检查时间：", displayDate(record), "检查地点：", record.location},
                new int[]{1, 1, 1, 1}, 13);
        y += 42;

        float[] columns = {MARGIN, 112, 145, 362, 445, WIDTH - MARGIN};
        row(canvas, y, 34, columns,
                new String[]{"检查类别", "序号", "检查内容及标准", "检查结果", "现场情况/问题"},
                new int[]{1, 1, 1, 1, 1}, 11);
        y += 34;

        final float detailHeight = 102;
        final float signatureHeight = 78;
        final float signatureY = HEIGHT - 31 - signatureHeight;
        final float detailY = signatureY - detailHeight;
        int itemCount = Math.max(1, record.items.size());
        float itemArea = detailY - y;
        float itemHeight = itemArea / itemCount;
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
                cell(canvas, columns[0], y, columns[1] - columns[0], itemHeight,
                        item.category, font + .5f, maximumLines, Paint.Align.CENTER);
                cell(canvas, columns[1], y, columns[2] - columns[1], itemHeight,
                        String.valueOf(item.order), font + 1, 1, Paint.Align.CENTER);
                String description = item.content;
                if (!item.standard.isBlank() && !item.standard.equals(item.content)) {
                    description += "\n标准：" + item.standard;
                }
                cell(canvas, columns[2], y, columns[3] - columns[2], itemHeight,
                        description, font, maximumLines, Paint.Align.LEFT);
                cell(canvas, columns[3], y, columns[4] - columns[3], itemHeight,
                        result(item.result), font + .5f, Math.min(2, maximumLines), Paint.Align.CENTER);
                cell(canvas, columns[4], y, columns[5] - columns[4], itemHeight,
                        item.problem, font, maximumLines, Paint.Align.LEFT);
                y += itemHeight;
            }
        }

        drawInspectionSummary(canvas, record, detailY, detailHeight);
        drawSignatures(canvas, record, signatureY, signatureHeight);
    }

    private void drawInspectionSummary(Canvas canvas, Inspection record, float y, float height) {
        rect(canvas, MARGIN, y, WIDTH - 2f * MARGIN, height);
        float split = MARGIN + 88;
        line(canvas, split, y, split, y + height);
        int yes = 0;
        List<String> problems = new ArrayList<>();
        for (InspectionItem item : record.items) {
            if ("PASS".equals(item.result)) yes++;
            if ("FAIL".equals(item.result)) problems.add(item.order + ". " + item.problem);
        }
        text(canvas, MARGIN + 6, y + 17, "检查情况：", 11, Paint.Align.LEFT, true);
        wrapped(canvas,
                problems.isEmpty()
                        ? "共检查 " + record.items.size() + " 项，全部选择“是”，未发现问题。"
                        : "共检查 " + record.items.size() + " 项，“是” " + yes + " 项，“否” "
                                + problems.size() + " 项。",
                split + 6, y + 4, WIDTH - MARGIN - split - 12, 10, 2, Paint.Align.LEFT);
        text(canvas, MARGIN + 6, y + 49, "整改意见：", 11, Paint.Align.LEFT, true);
        String details = problems.isEmpty() ? "无" : String.join("；", problems);
        wrapped(canvas, details, split + 6, y + 36,
                WIDTH - MARGIN - split - 12, 9, 3, Paint.Align.LEFT);
        if (!record.rectification.isBlank()) {
            text(canvas, MARGIN + 6, y + 83, "整改记录：", 11, Paint.Align.LEFT, true);
            wrapped(canvas, record.rectification + "（" + rectificationStatus(record.status) + "）",
                    split + 6, y + 69, WIDTH - MARGIN - split - 12, 9, 2, Paint.Align.LEFT);
        }
    }

    private void drawSignatures(Canvas canvas, Inspection record, float y, float height) {
        float x0 = MARGIN;
        float x1 = 112;
        float x2 = 310;
        float x3 = 400;
        float x4 = WIDTH - MARGIN;
        rect(canvas, x0, y, x1 - x0, height);
        rect(canvas, x1, y, x2 - x1, height);
        rect(canvas, x2, y, x3 - x2, height);
        rect(canvas, x3, y, x4 - x3, height);
        line(canvas, x1, y + height / 2, x2, y + height / 2);
        text(canvas, x0 + 12, y + height / 2 + 4, "检查人：", 12, Paint.Align.LEFT, true);
        text(canvas, x1 + 5, y + 14, "1.", 10, Paint.Align.LEFT, false);
        text(canvas, x1 + 5, y + height / 2 + 14, "2.", 10, Paint.Align.LEFT, false);
        text(canvas, x2 + 10, y + height / 2 + 4, "被检查人：", 12, Paint.Align.LEFT, true);

        List<Signature> signatures = repo.signatures(record.id);
        drawSignature(canvas, signatures, "INSPECTOR1", x1 + 22, y + 2,
                x2 - x1 - 25, height / 2 - 4);
        drawSignature(canvas, signatures, "INSPECTOR2", x1 + 22, y + height / 2 + 2,
                x2 - x1 - 25, height / 2 - 4);
        drawSignature(canvas, signatures, "INSPECTEE", x3 + 5, y + 4,
                x4 - x3 - 10, height - 8);
    }

    private void drawSignature(Canvas canvas, List<Signature> signatures, String role,
                               float x, float y, float width, float height) {
        for (Signature signature : signatures) {
            if (!role.equals(signature.role)) continue;
            Bitmap bitmap = BitmapFactory.decodeFile(signature.path);
            if (bitmap == null) return;
            canvas.drawBitmap(bitmap, null, fit(bitmap, x, y, width, height), paint);
            bitmap.recycle();
            return;
        }
    }

    private void drawPhotos(Canvas canvas, Inspection record, int start) {
        text(canvas, WIDTH / 2f, 31, formTitle(record) + " · 照片附件",
                20, Paint.Align.CENTER, true);
        text(canvas, MARGIN, 53, displayDate(record) + "  " + record.location,
                11, Paint.Align.LEFT, false);
        for (int i = 0; i < 4 && start + i < record.media.size(); i++) {
            Media media = record.media.get(start + i);
            int column = i % 2;
            int row = i / 2;
            float gap = 10;
            float width = (WIDTH - 2f * MARGIN - gap) / 2f;
            float height = 349;
            float x = MARGIN + column * (width + gap);
            float y = 66 + row * (height + 10);
            rect(canvas, x, y, width, height);
            Bitmap bitmap = BitmapFactory.decodeFile(media.localPath);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null,
                        fit(bitmap, x + 3, y + 3, width - 6, height - 28), paint);
                bitmap.recycle();
            }
            String label = "RECTIFICATION".equals(media.category)
                    ? "整改照片" : "RECHECK".equals(media.category) ? "复查照片" : "检查照片";
            text(canvas, x + 5, y + height - 9, label + "  " + media.location,
                    9, Paint.Align.LEFT, false);
        }
    }

    private String formTitle(Inspection record) {
        String value = record.templateName == null ? "安全检查" : record.templateName.trim();
        return value.endsWith("记录表") ? value : value + "记录表";
    }

    private String displayDate(Inspection record) {
        return record.date + (record.time == null || record.time.isBlank() ? "" : " " + record.time);
    }

    private String result(String value) {
        return "PASS".equals(value) ? "☑ 是\n□ 否"
                : "FAIL".equals(value) ? "□ 是\n☑ 否" : "□ 是\n□ 否";
    }

    private String rectificationStatus(String status) {
        return "RECTIFIED".equals(status) || "COMPLETED".equals(status)
                ? "已整改完成" : "尚未确认完成";
    }

    private RectF fit(Bitmap bitmap, float x, float y, float width, float height) {
        float scale = Math.min(width / bitmap.getWidth(), height / bitmap.getHeight());
        float targetWidth = bitmap.getWidth() * scale;
        float targetHeight = bitmap.getHeight() * scale;
        return new RectF(x + (width - targetWidth) / 2, y + (height - targetHeight) / 2,
                x + (width + targetWidth) / 2, y + (height + targetHeight) / 2);
    }

    private void cell(Canvas canvas, float x, float y, float width, float height,
                      String value, float size, int maximumLines, Paint.Align alignment) {
        rect(canvas, x, y, width, height);
        wrapped(canvas, value, x + 3, y + 2, width - 6, size,
                maximumLines, alignment);
    }

    private void row(Canvas canvas, float y, float height, float[] positions,
                     String[] values, int[] spans, float size) {
        int position = 0;
        for (int i = 0; i < values.length; i++) {
            int span = spans[i];
            float x = positions[position];
            float right = positions[Math.min(positions.length - 1, position + span)];
            rect(canvas, x, y, right - x, height);
            wrapped(canvas, values[i], x + 4, y + 5, right - x - 8,
                    size, 3, Paint.Align.CENTER);
            position += span;
        }
    }

    private void rect(Canvas canvas, float x, float y, float width, float height) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.7f);
        paint.setColor(Color.BLACK);
        canvas.drawRect(x, y, x + width, y + height, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.7f);
        paint.setColor(Color.BLACK);
        canvas.drawLine(x1, y1, x2, y2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void text(Canvas canvas, float x, float y, String value, float size,
                      Paint.Align alignment, boolean bold) {
        paint.setTextSize(size);
        paint.setTextAlign(alignment);
        paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        canvas.drawText(value == null ? "" : value, x, y, paint);
    }

    private void wrapped(Canvas canvas, String value, float x, float y, float width,
                         float size, int maximumLines, Paint.Align alignment) {
        if (value == null || maximumLines <= 0) return;
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < paragraph.length();) {
                int codePoint = paragraph.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                if (paint.measureText(line + character) > width && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(character);
                offset += Character.charCount(codePoint);
            }
            lines.add(line.toString());
        }
        int count = Math.min(maximumLines, lines.size());
        for (int i = 0; i < count; i++) {
            String line = lines.get(i);
            float drawX = alignment == Paint.Align.CENTER
                    ? x + (width - paint.measureText(line)) / 2f : x;
            canvas.drawText(line, drawX, y + size * (i + 1) * 1.18f, paint);
        }
    }
}
