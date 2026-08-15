package cn.safetyledger.pc;

import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Creates an editable A4 Word archive matching the mobile first-page form structure. */
public final class WordExporter {
    public static final int LAYOUT_VERSION = 6;

    private static final String FONT = "Microsoft YaHei";
    private static final int A4_WIDTH = 11906;
    private static final int A4_HEIGHT = 16838;
    private static final int LEFT_RIGHT_MARGIN = 480;
    private static final int TOP_MARGIN = 260;
    private static final int BOTTOM_MARGIN = 620;
    private static final int TABLE_WIDTH = 10940;

    private WordExporter() {}

    public static void write(ArchiveService.Record record, Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        try (XWPFDocument doc = new XWPFDocument()) {
            configurePage(doc);
            addTitle(doc, formTitle(record));
            addBasicRow(doc, record);
            addItems(doc, record);
            addSummary(doc, record);
            addSignatures(doc, record);
            addPhotoPages(doc, record, destination.getParent());
            addFooter(doc, record);
            try (OutputStream out = Files.newOutputStream(destination)) { doc.write(out); }
        }
    }

    private static void configurePage(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr() : doc.getDocument().getBody().addNewSectPr();
        CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        size.setW(BigInteger.valueOf(A4_WIDTH));
        size.setH(BigInteger.valueOf(A4_HEIGHT));
        CTPageMar margins = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        margins.setLeft(BigInteger.valueOf(LEFT_RIGHT_MARGIN));
        margins.setRight(BigInteger.valueOf(LEFT_RIGHT_MARGIN));
        margins.setTop(BigInteger.valueOf(TOP_MARGIN));
        margins.setBottom(BigInteger.valueOf(BOTTOM_MARGIN));
        margins.setHeader(BigInteger.ZERO);
        margins.setFooter(BigInteger.valueOf(180));
    }

    private static void addTitle(XWPFDocument doc, String value) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        exactParagraph(p, 560);
        run(p, value, 24, true);
    }

    private static void addBasicRow(XWPFDocument doc, ArchiveService.Record record) {
        int[] widths = {1900, 3500, 2000, 3540};
        XWPFTable table = table(doc, 1, 4, widths);
        exactRow(table.getRow(0), 720);
        cell(table.getRow(0).getCell(0), "检查时间：", 12, true, ParagraphAlignment.LEFT);
        cell(table.getRow(0).getCell(1), displayDate(record), 12, false, ParagraphAlignment.LEFT);
        cell(table.getRow(0).getCell(2), "检查地点：", 12, true, ParagraphAlignment.LEFT);
        cell(table.getRow(0).getCell(3), blank(record.location, ""), 12, false, ParagraphAlignment.LEFT);
    }

    /**
     * The item block always occupies the same total height. Only real items create rows.
     * Row heights are content-aware; font size is reduced only when the content cannot fit.
     */
    private static void addItems(XWPFDocument doc, ArchiveService.Record record) {
        int[] widths = {1760, 660, 4340, 1660, 2520};
        int realCount = record.items == null ? 0 : record.items.size();
        if (realCount == 0) {
            XWPFTable table = table(doc, 2, 5, widths);
            String[] heads = {"检查类别", "序号", "检查内容及标准", "检查结果", "现场情况/问题"};
            exactRow(table.getRow(0), FormLayout.ITEM_HEADER_TWIPS);
            for (int i = 0; i < heads.length; i++) cell(table.getRow(0).getCell(i), heads[i], 10, true, ParagraphAlignment.CENTER);
            exactRow(table.getRow(1), FormLayout.ITEM_BODY_TWIPS);
            mergeHorizontal(table.getRow(1), 0, 4);
            cell(table.getRow(1).getCell(0), "当前模板没有检查项目", 11, false, ParagraphAlignment.CENTER);
            return;
        }

        FormLayout.Result layout = FormLayout.calculate(record);
        XWPFTable table = table(doc, realCount + 1, 5, widths);
        String[] heads = {"检查类别", "序号", "检查内容及标准", "检查结果", "现场情况/问题"};
        exactRow(table.getRow(0), FormLayout.ITEM_HEADER_TWIPS);
        for (int i = 0; i < heads.length; i++) cell(table.getRow(0).getCell(i), heads[i], 10, true, ParagraphAlignment.CENTER);

        for (int r = 0; r < realCount; r++) {
            ArchiveService.Item item = record.items.get(r);
            XWPFTableRow row = table.getRow(r + 1);
            exactRow(row, layout.rowHeightsTwips[r]);
            int itemFont = layout.itemFontSize;
            cell(row.getCell(0), blank(item.category, ""), Math.min(10, itemFont + 1), false, ParagraphAlignment.CENTER);
            cell(row.getCell(1), String.valueOf(item.order), Math.min(11, itemFont + 1), false, ParagraphAlignment.CENTER);
            String description = blank(item.content, "");
            if (item.standard != null && !item.standard.isBlank() && !item.standard.equals(item.content)) {
                description += "\n标准：" + item.standard;
            }
            cell(row.getCell(2), description, itemFont, false, ParagraphAlignment.LEFT);
            cell(row.getCell(3), result(item.result), Math.min(10, itemFont + 1), false, ParagraphAlignment.CENTER);
            cell(row.getCell(4), blank(item.problem, ""), itemFont, false, ParagraphAlignment.LEFT);
        }
    }

    private static void addSummary(XWPFDocument doc, ArchiveService.Record record) {
        int[] widths = {1760, 9180};
        XWPFTable table = table(doc, 2, 2, widths);
        for (XWPFTableRow row : table.getRows()) exactRow(row, FormLayout.SUMMARY_ROW_TWIPS);

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
        String rectification = rectificationValue.isBlank() ? status : rectificationValue + "（" + status + "）";

        cell(table.getRow(0).getCell(0), "整改意见：", 10, true, ParagraphAlignment.LEFT);
        cell(table.getRow(0).getCell(1), opinion, fitSummaryFont(opinion), false, ParagraphAlignment.LEFT);
        cell(table.getRow(1).getCell(0), "整改记录：", 10, true, ParagraphAlignment.LEFT);
        cell(table.getRow(1).getCell(1), rectification, fitSummaryFont(rectification), false, ParagraphAlignment.LEFT);
    }

    private static int fitSummaryFont(String text) {
        int length = text == null ? 0 : text.length();
        if (length > 100) return 7;
        if (length > 65) return 8;
        return 9;
    }

    private static void addSignatures(XWPFDocument doc, ArchiveService.Record record) {
        int[] widths = {1760, 3960, 1800, 3420};
        XWPFTable table = table(doc, 2, 4, widths);
        exactRow(table.getRow(0), FormLayout.SIGNATURE_ROW_TWIPS);
        exactRow(table.getRow(1), FormLayout.SIGNATURE_ROW_TWIPS);
        verticalMerge(table, 0, 0, 1);
        verticalMerge(table, 2, 0, 1);
        verticalMerge(table, 3, 0, 1);
        cell(table.getRow(0).getCell(0), "检查人：", 11, true, ParagraphAlignment.LEFT);
        signatureCell(table.getRow(0).getCell(1), "1.", record.signature("INSPECTOR1"), 145, 24);
        signatureCell(table.getRow(1).getCell(1), "2.", record.signature("INSPECTOR2"), 145, 24);
        cell(table.getRow(0).getCell(2), "被检查人：", 11, true, ParagraphAlignment.LEFT);
        signatureCell(table.getRow(0).getCell(3), "", record.signature("INSPECTEE"), 140, 50);
    }

    /** Photos are always placed after an explicit page break, four per page. */
    private static void addPhotoPages(XWPFDocument doc, ArchiveService.Record record, Path folder) {
        List<Photo> photos = archivePhotos(folder);
        if (photos.isEmpty()) return;
        int index = 0;
        while (index < photos.size()) {
            XWPFParagraph pageBreak = doc.createParagraph();
            pageBreak.setPageBreak(true);
            pageBreak.setSpacingBefore(0); pageBreak.setSpacingAfter(0);

            XWPFParagraph heading = doc.createParagraph();
            heading.setAlignment(ParagraphAlignment.CENTER);
            exactParagraph(heading, 430);
            run(heading, formTitle(record) + " · 照片附件", 15, true);

            XWPFTable table = table(doc, 2, 2, new int[]{5470, 5470});
            exactRow(table.getRow(0), 4500);
            exactRow(table.getRow(1), 4500);
            for (int slot = 0; slot < 4; slot++) {
                int r = slot / 2, c = slot % 2;
                XWPFTableCell cell = table.getRow(r).getCell(c);
                if (index < photos.size()) photoCell(cell, photos.get(index++));
                else cell(cell, "", 9, false, ParagraphAlignment.CENTER);
            }
        }
    }

    private static List<Photo> archivePhotos(Path folder) {
        List<Photo> out = new ArrayList<>();
        addPhotos(out, folder.resolve("检查照片"), "检查照片");
        addPhotos(out, folder.resolve("整改照片"), "整改照片");
        addPhotos(out, folder.resolve("复查照片"), "复查照片");
        return out;
    }

    private static void addPhotos(List<Photo> out, Path dir, String category) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).filter(WordExporter::isImage)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> out.add(new Photo(p, category)));
        } catch (Exception ignored) {}
    }

    private static boolean isImage(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    private static void photoCell(XWPFTableCell cell, Photo photo) {
        while (!cell.getParagraphs().isEmpty()) cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(ParagraphAlignment.CENTER); p.setSpacingBefore(0); p.setSpacingAfter(0);
        try (InputStream in = Files.newInputStream(photo.path)) {
            int[] fitted = fitImage(photo.path, 235, 178);
            String lower = photo.path.getFileName().toString().toLowerCase(Locale.ROOT);
            int type = lower.endsWith(".png") ? XWPFDocument.PICTURE_TYPE_PNG : XWPFDocument.PICTURE_TYPE_JPEG;
            p.createRun().addPicture(in, type, photo.path.getFileName().toString(), Units.toEMU(fitted[0]), Units.toEMU(fitted[1]));
        } catch (Exception ignored) {}
        XWPFParagraph caption = cell.addParagraph();
        caption.setAlignment(ParagraphAlignment.CENTER); caption.setSpacingBefore(0); caption.setSpacingAfter(0);
        run(caption, photo.category + " · " + photo.path.getFileName(), 8, false);
    }

    private static void addFooter(XWPFDocument doc, ArchiveService.Record record) {
        XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph p = footer.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingBefore(0); p.setSpacingAfter(0);
        run(p, blank(record.date, "") + "  第", 8, false);
        pageField(p, "PAGE");
        run(p, "页/共", 8, false);
        pageField(p, "NUMPAGES");
        run(p, "页", 8, false);
    }

    private static void pageField(XWPFParagraph paragraph, String instruction) {
        XWPFRun beginRun = paragraph.createRun();
        beginRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
        XWPFRun instrRun = paragraph.createRun();
        CTText instr = instrRun.getCTR().addNewInstrText();
        instr.setSpace(org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute.Space.PRESERVE);
        instr.setStringValue(" " + instruction + " ");
        XWPFRun sepRun = paragraph.createRun();
        sepRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
        run(paragraph, "1", 8, false);
        XWPFRun endRun = paragraph.createRun();
        endRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
    }

    private static XWPFTable table(XWPFDocument doc, int rows, int cols, int[] widths) {
        XWPFTable table = doc.createTable(rows, cols);
        table.setWidth(TABLE_WIDTH);
        table.setCellMargins(36, 55, 36, 55);
        CTTblPr pr = table.getCTTbl().getTblPr();
        if (pr == null) pr = table.getCTTbl().addNewTblPr();
        CTTblLayoutType layout = pr.isSetTblLayout() ? pr.getTblLayout() : pr.addNewTblLayout();
        layout.setType(STTblLayoutType.FIXED);
        CTTblGrid grid = table.getCTTbl().getTblGrid();
        if (grid == null) grid = table.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int width : widths) grid.addNewGridCol().setW(BigInteger.valueOf(width));
        for (XWPFTableRow row : table.getRows()) {
            row.setCantSplitRow(true);
            for (int i = 0; i < row.getTableCells().size() && i < widths.length; i++) {
                row.getCell(i).setWidth(Integer.toString(widths[i]));
                row.getCell(i).setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
            }
        }
        return table;
    }

    private static void exactRow(XWPFTableRow row, int twips) {
        row.setHeight(twips);
        row.setHeightRule(TableRowHeightRule.EXACT);
        row.setCantSplitRow(true);
    }

    private static void cell(XWPFTableCell cell, String value, int size, boolean bold, ParagraphAlignment alignment) {
        while (!cell.getParagraphs().isEmpty()) cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(alignment);
        p.setSpacingBefore(0); p.setSpacingAfter(0);
        p.setIndentationLeft(0); p.setIndentationRight(0);
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        String[] lines = (value == null ? "" : value).split("\\n", -1);
        XWPFRun r = run(p, lines.length == 0 ? "" : lines[0], size, bold);
        for (int i = 1; i < lines.length; i++) { r.addBreak(); r.setText(lines[i]); }
    }

    private static XWPFRun run(XWPFParagraph p, String value, int size, boolean bold) {
        XWPFRun run = p.createRun();
        run.setBold(bold); run.setFontSize(size); run.setFontFamily(FONT);
        CTRPr rPr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = rPr.sizeOfRFontsArray() > 0 ? rPr.getRFontsArray(0) : rPr.addNewRFonts();
        fonts.setAscii(FONT); fonts.setHAnsi(FONT); fonts.setEastAsia(FONT);
        run.setText(value == null ? "" : value);
        return run;
    }

    private static void exactParagraph(XWPFParagraph p, int lineTwips) {
        CTPPr pPr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTSpacing spacing = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        spacing.setBefore(BigInteger.ZERO); spacing.setAfter(BigInteger.ZERO);
        spacing.setLine(BigInteger.valueOf(lineTwips)); spacing.setLineRule(STLineSpacingRule.EXACT);
    }

    private static void signatureCell(XWPFTableCell cell, String prefix, Path image, int maxWPt, int maxHPt) {
        while (!cell.getParagraphs().isEmpty()) cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(ParagraphAlignment.LEFT); p.setSpacingBefore(0); p.setSpacingAfter(0);
        if (!prefix.isBlank()) run(p, prefix + " ", 9, false);
        if (image == null || !Files.isRegularFile(image)) return;
        try (InputStream in = Files.newInputStream(image)) {
            int[] fitted = fitImage(image, maxWPt, maxHPt);
            String lower = image.getFileName().toString().toLowerCase(Locale.ROOT);
            int type = lower.endsWith(".png") ? XWPFDocument.PICTURE_TYPE_PNG : XWPFDocument.PICTURE_TYPE_JPEG;
            p.createRun().addPicture(in, type, image.getFileName().toString(), Units.toEMU(fitted[0]), Units.toEMU(fitted[1]));
        } catch (Exception ignored) {}
    }

    private static int[] fitImage(Path image, int maxW, int maxH) {
        try {
            BufferedImage buffered = ImageIO.read(image.toFile());
            if (buffered == null || buffered.getWidth() <= 0 || buffered.getHeight() <= 0) return new int[]{maxW, maxH};
            double ratio = Math.min((double) maxW / buffered.getWidth(), (double) maxH / buffered.getHeight());
            return new int[]{Math.max(1, (int) Math.round(buffered.getWidth() * ratio)), Math.max(1, (int) Math.round(buffered.getHeight() * ratio))};
        } catch (Exception ignored) { return new int[]{maxW, maxH}; }
    }

    private static void verticalMerge(XWPFTable table, int col, int fromRow, int toRow) {
        for (int row = fromRow; row <= toRow; row++) {
            XWPFTableCell cell = table.getRow(row).getCell(col);
            CTTcPr pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
            CTVMerge merge = pr.isSetVMerge() ? pr.getVMerge() : pr.addNewVMerge();
            merge.setVal(row == fromRow ? STMerge.RESTART : STMerge.CONTINUE);
        }
    }

    private static void mergeHorizontal(XWPFTableRow row, int fromCol, int toCol) {
        XWPFTableCell first = row.getCell(fromCol);
        CTTcPr firstPr = first.getCTTc().isSetTcPr() ? first.getCTTc().getTcPr() : first.getCTTc().addNewTcPr();
        CTDecimalNumber span = firstPr.isSetGridSpan() ? firstPr.getGridSpan() : firstPr.addNewGridSpan();
        span.setVal(BigInteger.valueOf(toCol - fromCol + 1L));
        for (int i = toCol; i > fromCol; i--) row.getCtRow().removeTc(i);
    }

    private static String formTitle(ArchiveService.Record record) {
        String name = blank(record == null ? null : record.templateName, "安全检查").trim();
        if (name.endsWith("记录表")) return name;
        if (name.endsWith("记录")) return name + "表";
        return name + "记录表";
    }

    private static String displayDate(ArchiveService.Record record) {
        String date = blank(record.date, "");
        String time = blank(record.time, "");
        return time.isBlank() ? date : date + " " + time;
    }

    private static String result(String value) {
        return "PASS".equals(value) ? "是" : "FAIL".equals(value) ? "否" : "未填写";
    }

    private static String rectificationStatus(String value) {
        if (value == null) return "";
        return switch (value) {
            case "PENDING_RECTIFICATION" -> "待整改";
            case "RECTIFYING" -> "整改中";
            case "RECTIFIED" -> "已整改完成";
            case "COMPLETED" -> "检查完成";
            default -> value;
        };
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Photo(Path path, String category) {}
}
