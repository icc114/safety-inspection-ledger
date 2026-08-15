package cn.safetyledger.pc;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WordExporterTest {
    @Test void wordFormUsesFixedNineSlotA4FirstPageStructure() throws Exception {
        ArchiveService.Record record = new ArchiveService.Record();
        record.id = "test-record";
        record.templateName = "安全检查记录";
        record.date = "2026-08-15";
        record.time = "15:14";
        record.location = "测试地点";
        record.status = "RECTIFIED";
        record.rectification = "已完成整改";
        ArchiveService.Item item = new ArchiveService.Item();
        item.order = 1;
        item.category = "消防设施";
        item.content = "灭火器是否完好";
        item.standard = "压力正常、在有效期内";
        item.result = "FAIL";
        item.problem = "1具灭火器过期";
        record.items.add(item);
        for (int i = 0; i < 5; i++) record.media.add(new ArchiveService.Media());

        Path out = Files.createTempFile("safety-word-layout-", ".docx");
        try {
            WordExporter.write(record, out);
            try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(out))) {
                assertEquals("安全检查记录表", doc.getParagraphs().get(0).getText());
                assertEquals(4, doc.getTables().size());
                assertEquals(1, doc.getTables().get(0).getRows().size());
                assertEquals(4, doc.getTables().get(0).getRow(0).getTableCells().size());
                assertTrue(doc.getTables().get(0).getText().contains("检查时间："));
                assertTrue(doc.getTables().get(0).getText().contains("检查地点："));
                assertFalse(doc.getTables().get(0).getText().contains("检查类型"));

                // Header + nine fixed item slots. Adding the ninth item must not shrink earlier rows.
                assertEquals(10, doc.getTables().get(1).getRows().size());
                assertTrue(rowText(doc.getTables().get(1).getRow(1)).contains("灭火器是否完好"));
                assertTrue(rowText(doc.getTables().get(1).getRow(9)).isBlank());

                assertFalse(doc.getTables().get(2).getText().contains("检查情况："));
                assertTrue(doc.getTables().get(2).getText().contains("整改意见："));
                assertTrue(doc.getTables().get(2).getText().contains("1. 1具灭火器过期"));
                assertTrue(doc.getTables().get(2).getText().contains("整改记录："));
                assertTrue(doc.getTables().get(3).getText().contains("检查人："));
                assertTrue(doc.getTables().get(3).getText().contains("被检查人："));
                assertTrue(doc.getFooterList().stream().anyMatch(f -> f.getText().contains("第1页/共3页")));
            }
        } finally { Files.deleteIfExists(out); }
    }

    private static String rowText(XWPFTableRow row) {
        StringBuilder out = new StringBuilder();
        row.getTableCells().forEach(cell -> out.append(cell.getText()));
        return out.toString();
    }
}
