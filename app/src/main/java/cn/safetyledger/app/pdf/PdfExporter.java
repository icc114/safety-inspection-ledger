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

public final class PdfExporter {
    private static final int WIDTH = 595;
    private static final int HEIGHT = 842;
    private static final int MARGIN = 28;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LedgerRepository repo;

    public PdfExporter(Context context) {
        repo = new LedgerRepository(context);
        paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
    }

    private static final class PageSpec {
        final Inspection record;
        final int start;
        final boolean table;
        final boolean lastTable;

        PageSpec(Inspection record, boolean table, int start, boolean lastTable) {
            this.record = record;
            this.table = table;
            this.start = start;
            this.lastTable = lastTable;
        }
    }

    public void export(List<Inspection> records, OutputStream output) throws IOException {
        validateAssets(records);
        List<PageSpec> pages = new ArrayList<>();
        for (Inspection record : records) {
            int chunks = Math.max(1, (record.items.size() + 7) / 8);
            for (int i = 0; i < chunks; i++) {
                pages.add(new PageSpec(record, true, i * 8, i == chunks - 1));
            }
            for (int i = 0; i < record.media.size(); i += 4) {
                pages.add(new PageSpec(record, false, i, false));
            }
        }
        Map<String, Integer> totals = new HashMap<>();
        for (PageSpec page : pages) {
            totals.put(page.record.date, totals.getOrDefault(page.record.date, 0) + 1);
        }
        Map<String, Integer> numbers = new HashMap<>();
        PdfDocument document = new PdfDocument();
        try {
            for (int i = 0; i < pages.size(); i++) {
                PageSpec spec = pages.get(i);
                int number = numbers.getOrDefault(spec.record.date, 0) + 1;
                numbers.put(spec.record.date, number);
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
                        WIDTH, HEIGHT, i + 1).create());
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                if (spec.table) drawTable(canvas, spec.record, spec.start, spec.lastTable);
                else drawPhotos(canvas, spec.record, spec.start);
                text(canvas, WIDTH / 2f, HEIGHT - 16,
                        spec.record.date + "  第" + number + "页/共"
                                + totals.get(spec.record.date) + "页",
                        10, Paint.Align.CENTER, false);
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
                    throw new IOException("记录 " + record.date + " 缺少" + media.category
                            + "文件；已停止导出，避免生成不完整 PDF");
                }
            }
            for (Signature signature : repo.signatures(record.id)) {
                if (signature.path == null || !new File(signature.path).isFile()) {
                    throw new IOException("记录 " + record.date + " 缺少" + signature.role
                            + "签名文件，已停止导出");
                }
            }
        }
    }

    private void drawTable(Canvas canvas, Inspection record, int start, boolean lastTable) {
        float y = 31;
        text(canvas, WIDTH / 2f, y, record.templateName + "记录表",
                25, Paint.Align.CENTER, true);
        y = 49;
        float[] basic = {MARGIN, 120, 320, 405, WIDTH - MARGIN};
        row(canvas, y, 38, basic,
                new String[]{"检查日期", record.date, "检查地点", record.location},
                new int[]{1, 1, 1, 1});
        y += 38;

        float[] columns = {MARGIN, 112, 145, 372, 445, WIDTH - MARGIN};
        row(canvas, y, 34, columns,
                new String[]{"检查类别", "序号", "检查内容及标准", "是/否", "现场情况、问题及整改要求"},
                new int[]{1, 1, 1, 1, 1});
        y += 34;
        int end = Math.min(start + 8, record.items.size());
        int count = Math.max(1, end - start);
        float available = lastTable ? 475 : 650;
        float itemHeight = Math.min(70, available / count);
        float font = itemHeight < 52 ? 9 : 10;
        for (int index = start; index < end; index++) {
            InspectionItem item = record.items.get(index);
            cell(canvas, columns[0], y, columns[1] - columns[0], itemHeight,
                    item.category, font + 1, 4);
            cell(canvas, columns[1], y, columns[2] - columns[1], itemHeight,
                    String.valueOf(item.order), 11, 2);
            String description = item.content;
            if (!item.standard.isBlank() && !item.standard.equals(item.content)) {
                description += "\n标准：" + item.standard;
            }
            cell(canvas, columns[2], y, columns[3] - columns[2], itemHeight,
                    description, font, 7);
            cell(canvas, columns[3], y, columns[4] - columns[3], itemHeight,
                    result(item.result), font + 1, 3);
            cell(canvas, columns[4], y, columns[5] - columns[4], itemHeight,
                    item.problem, font, 7);
            y += itemHeight;
        }
        if (!lastTable) {
            text(canvas, WIDTH / 2f, y + 24, "检查项目续下页", 11,
                    Paint.Align.CENTER, false);
            return;
        }

        boolean hasProblem = false;
        for (InspectionItem item : record.items) if ("FAIL".equals(item.result)) hasProblem = true;
        if (hasProblem) {
            float rectificationHeight = 78;
            rect(canvas, MARGIN, y, WIDTH - 2 * MARGIN, rectificationHeight);
            wrapped(canvas, "整改记录：" + blank(record.rectification)
                            + "\n整改确认：" + rectificationStatus(record.status)
                            + (record.recheck.isBlank() ? "" : "\n复查说明：" + record.recheck),
                    MARGIN + 7, y + 6, WIDTH - 2 * MARGIN - 14, 11, 5);
            y += rectificationHeight;
        }

        float signatureHeight = Math.min(88, HEIGHT - y - 44);
        float width = (WIDTH - 2f * MARGIN) / 3f;
        String[] roles = {"INSPECTOR1", "INSPECTOR2", "INSPECTEE"};
        String[] names = {"检查人1", "检查人2", "被检查人"};
        List<Signature> signatures = repo.signatures(record.id);
        for (int i = 0; i < roles.length; i++) {
            float x = MARGIN + i * width;
            rect(canvas, x, y, width, signatureHeight);
            text(canvas, x + 5, y + 16, names[i] + "：", 10, Paint.Align.LEFT, true);
            for (Signature signature : signatures) {
                if (!roles[i].equals(signature.role)) continue;
                Bitmap bitmap = BitmapFactory.decodeFile(signature.path);
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null,
                            fit(bitmap, x + 8, y + 20, width - 16, signatureHeight - 24), paint);
                    bitmap.recycle();
                }
            }
        }
    }

    private void drawPhotos(Canvas canvas, Inspection record, int start) {
        text(canvas, WIDTH / 2f, 30, record.templateName + " · 照片附件",
                22, Paint.Align.CENTER, true);
        text(canvas, MARGIN, 52, record.date + "  " + record.location,
                11, Paint.Align.LEFT, false);
        for (int i = 0; i < 4 && start + i < record.media.size(); i++) {
            Media media = record.media.get(start + i);
            int column = i % 2;
            int row = i / 2;
            float x = MARGIN + column * (WIDTH - 2f * MARGIN + 10) / 2f;
            float y = 70 + row * 350;
            float width = (WIDTH - 2f * MARGIN - 10) / 2f;
            float height = 300;
            rect(canvas, x, y, width, height);
            Bitmap bitmap = BitmapFactory.decodeFile(media.localPath);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null,
                        fit(bitmap, x + 3, y + 3, width - 6, height - 28), paint);
                bitmap.recycle();
            }
            String label = "RECTIFICATION".equals(media.category)
                    ? "整改照片" : "RECHECK".equals(media.category) ? "复查照片" : "检查照片";
            text(canvas, x + 4, y + height - 8, label + "  " + media.location,
                    10, Paint.Align.LEFT, false);
        }
    }

    private void cell(Canvas canvas, float x, float y, float width, float height,
                      String value, float size, int maximumLines) {
        rect(canvas, x, y, width, height);
        wrapped(canvas, value, x + 4, y + 4, width - 8, size, maximumLines);
    }

    private RectF fit(Bitmap bitmap, float x, float y, float width, float height) {
        float scale = Math.min(width / bitmap.getWidth(), height / bitmap.getHeight());
        float targetWidth = bitmap.getWidth() * scale;
        float targetHeight = bitmap.getHeight() * scale;
        return new RectF(x + (width - targetWidth) / 2, y + (height - targetHeight) / 2,
                x + (width + targetWidth) / 2, y + (height + targetHeight) / 2);
    }

    private String result(String value) {
        return "PASS".equals(value) ? "☑ 是\n□ 否" : "FAIL".equals(value)
                ? "□ 是\n☑ 否" : "□ 是\n□ 否";
    }

    private String rectificationStatus(String status) {
        return "RECTIFIED".equals(status) || "COMPLETED".equals(status)
                ? "☑ 已整改完成" : "□ 尚未确认完成";
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? "尚未填写" : value;
    }

    private void row(Canvas canvas, float y, float height, float[] positions,
                     String[] values, int[] spans) {
        int position = 0;
        for (int i = 0; i < values.length; i++) {
            int span = spans[i];
            float x = positions[position];
            float right = positions[Math.min(positions.length - 1, position + span)];
            rect(canvas, x, y, right - x, height);
            wrapped(canvas, values[i], x + 5, y + 7, right - x - 10, 11, 3);
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

    private void text(Canvas canvas, float x, float y, String value, float size,
                      Paint.Align alignment, boolean bold) {
        paint.setTextSize(size);
        paint.setTextAlign(alignment);
        paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        canvas.drawText(value == null ? "" : value, x, y, paint);
    }

    private void wrapped(Canvas canvas, String value, float x, float y, float width,
                         float size, int maximumLines) {
        if (value == null) return;
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.LEFT);
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
        for (int i = 0; i < Math.min(maximumLines, lines.size()); i++) {
            canvas.drawText(lines.get(i), x, y + size * (i + 1) * 1.25f, paint);
        }
    }
}

