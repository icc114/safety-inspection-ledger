package cn.safetyledger.pc;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WordExporterTest {
    @Test void wordFormUsesOnlyRealItemsAndKeepsFixedFirstPageBlocks() throws Exception {
        ArchiveService.Record record = recordWithItems(8);
        record.items.get(2).content = "这一项检查内容比较长，用于验证系统会根据实际文字量自动增加这一行高度，而不是给所有检查项使用完全相同的高度";
        record.items.get(2).standard = "标准内容也比较长，需要和检查内容一起参与行高计算，并且不能把整改意见和签名区域挤到第二页";

        Path dir = Files.createTempDirectory("safety-word-layout-");
        Path signatureDir = dir.resolve("签名"); Files.createDirectories(signatureDir);
        Path signature = signatureDir.resolve("INSPECTOR1.png");
        BufferedImage sig = new BufferedImage(160, 50, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(sig, "png", signature.toFile());
        record.signatures.put("INSPECTOR1", signature);
        Path out = dir.resolve("检查记录.docx");
        try {
            WordExporter.write(record, out);
            try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(out))) {
                assertEquals("安全检查记录表", doc.getParagraphs().get(0).getText());
                assertEquals(4, doc.getTables().size());
                assertEquals(9, doc.getTables().get(1).getRows().size(), "表头 + 8 个实际检查项，不允许补空白格");
                assertTrue(rowText(doc.getTables().get(1).getRow(8)).contains("第8项"));

                int totalItemHeight = 0;
                for (int i = 1; i <= 8; i++) totalItemHeight += doc.getTables().get(1).getRow(i).getHeight();
                assertEquals(FormLayout.ITEM_BODY_TWIPS, totalItemHeight, "检查项目区域总高度必须固定");
                assertNotEquals(doc.getTables().get(1).getRow(1).getHeight(), doc.getTables().get(1).getRow(3).getHeight(),
                        "文字较长的检查项应获得更高行高");

                assertTrue(doc.getTables().get(2).getText().contains("整改意见："));
                assertTrue(doc.getTables().get(2).getText().contains("整改记录："));
                assertTrue(doc.getTables().get(3).getText().contains("检查人："));
                assertTrue(doc.getTables().get(3).getText().contains("被检查人："));
                assertFalse(doc.getAllPictures().isEmpty(), "有签名时必须写入 Word");
            }
        } finally { deleteTree(dir); }
    }

    @Test void twelveLongItemsShrinkFontOnlyWhenNeeded() {
        ArchiveService.Record record = recordWithItems(12);
        for (ArchiveService.Item item : record.items) {
            item.content = "较长检查内容较长检查内容较长检查内容较长检查内容较长检查内容较长检查内容";
            item.standard = "较长检查标准较长检查标准较长检查标准较长检查标准较长检查标准";
            item.problem = "现场情况描述较长现场情况描述较长现场情况描述较长";
        }
        FormLayout.Result result = FormLayout.calculate(record);
        assertTrue(result.itemFontSize < FormLayout.MAX_ITEM_FONT);
        assertEquals(12, result.rowHeightsTwips.length);
        assertEquals(FormLayout.ITEM_BODY_TWIPS, result.totalItemHeight());
    }

    private static ArchiveService.Record recordWithItems(int count) {
        ArchiveService.Record record = new ArchiveService.Record();
        record.id = "test-record";
        record.templateName = "安全检查记录";
        record.date = "2026-08-15";
        record.time = "15:14";
        record.location = "测试地点";
        record.status = "RECTIFIED";
        record.rectification = "已完成整改";
        for (int i = 1; i <= count; i++) {
            ArchiveService.Item item = new ArchiveService.Item();
            item.order = i;
            item.category = "检查类别" + i;
            item.content = "第" + i + "项检查内容";
            item.standard = "第" + i + "项检查标准";
            item.result = i == 2 ? "FAIL" : "PASS";
            item.problem = i == 2 ? "发现问题并要求整改" : "";
            record.items.add(item);
        }
        return record;
    }

    private static String rowText(XWPFTableRow row) {
        StringBuilder out = new StringBuilder();
        row.getTableCells().forEach(cell -> out.append(cell.getText()));
        return out.toString();
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted((a,b) -> b.compareTo(a)).toList()) Files.deleteIfExists(path);
        }
    }
}
