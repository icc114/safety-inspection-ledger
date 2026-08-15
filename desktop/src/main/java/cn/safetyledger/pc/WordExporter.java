package cn.safetyledger.pc;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates an editable Word inspection record; photos remain as original files beside it. */
public final class WordExporter {
    private WordExporter() {}

    public static void write(ArchiveService.Record record, Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph title = doc.createParagraph(); title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun(); titleRun.setBold(true); titleRun.setFontSize(18);
            titleRun.setText(record.templateName.endsWith("记录表") ? record.templateName : record.templateName + "记录表");

            XWPFTable basic = doc.createTable(2, 4); basic.setWidth("100%");
            set(basic.getRow(0).getCell(0), "检查时间", true); set(basic.getRow(0).getCell(1), record.date + (record.time.isBlank()?"":" " + record.time), false);
            set(basic.getRow(0).getCell(2), "检查地点", true); set(basic.getRow(0).getCell(3), record.location, false);
            set(basic.getRow(1).getCell(0), "检查类型", true); set(basic.getRow(1).getCell(1), record.type, false);
            set(basic.getRow(1).getCell(2), "状态", true); set(basic.getRow(1).getCell(3), status(record.status), false);

            XWPFParagraph spacer = doc.createParagraph(); spacer.setSpacingAfter(0);
            XWPFTable table = doc.createTable(1, 5); table.setWidth("100%");
            String[] heads={"检查类别","序号","检查内容及标准","检查结果","现场情况/问题"};
            for(int i=0;i<heads.length;i++)set(table.getRow(0).getCell(i),heads[i],true);
            for(ArchiveService.Item item:record.items){
                XWPFTableRow row=table.createRow();
                set(row.getCell(0),item.category,false);set(row.getCell(1),String.valueOf(item.order),false);
                String description=item.content+(item.standard.isBlank()||item.standard.equals(item.content)?"":"\n标准："+item.standard);
                set(row.getCell(2),description,false);set(row.getCell(3),result(item.result),false);set(row.getCell(4),item.problem,false);
            }

            XWPFParagraph detailTitle=doc.createParagraph(); XWPFRun dr=detailTitle.createRun();dr.setBold(true);dr.setText("整改及复查记录");
            XWPFTable detail=doc.createTable(2,2);detail.setWidth("100%");
            set(detail.getRow(0).getCell(0),"整改记录",true);set(detail.getRow(0).getCell(1),blank(record.rectification),false);
            set(detail.getRow(1).getCell(0),"复查说明",true);set(detail.getRow(1).getCell(1),blank(record.recheck),false);

            XWPFParagraph signTitle=doc.createParagraph();XWPFRun sr=signTitle.createRun();sr.setBold(true);sr.setText("现场签名");
            XWPFTable signs=doc.createTable(1,3);signs.setWidth("100%");
            addSignature(signs.getRow(0).getCell(0),"检查人1",record.signature("INSPECTOR1"));
            addSignature(signs.getRow(0).getCell(1),"检查人2",record.signature("INSPECTOR2"));
            addSignature(signs.getRow(0).getCell(2),"被检查人",record.signature("INSPECTEE"));

            try(OutputStream out=Files.newOutputStream(destination)){doc.write(out);}
        }
    }

    private static void set(XWPFTableCell cell,String value,boolean bold){
        cell.removeParagraph(0);XWPFParagraph p=cell.addParagraph();XWPFRun r=p.createRun();r.setBold(bold);r.setFontSize(10);r.setText(value==null?"":value);
    }
    private static void addSignature(XWPFTableCell cell,String label,Path image){
        cell.removeParagraph(0);XWPFParagraph p=cell.addParagraph();XWPFRun labelRun=p.createRun();labelRun.setBold(true);labelRun.setText(label+"：\n");
        if(image==null||!Files.isRegularFile(image)){p.createRun().setText("（未签名）");return;}
        try(InputStream in=Files.newInputStream(image)){
            int type=image.getFileName().toString().toLowerCase().endsWith(".png")?XWPFDocument.PICTURE_TYPE_PNG:XWPFDocument.PICTURE_TYPE_JPEG;
            p.createRun().addPicture(in,type,image.getFileName().toString(),Units.toEMU(120),Units.toEMU(45));
        }catch(Exception e){p.createRun().setText("（签名图片读取失败）");}
    }
    private static String result(String v){return "PASS".equals(v)?"是":"FAIL".equals(v)?"否":"未填写";}
    private static String status(String v){return switch(v){case "RECTIFIED","COMPLETED"->"已完成";case "RECTIFYING","PENDING_RECTIFICATION"->"整改中";default->v;};}
    private static String blank(String v){return v==null||v.isBlank()?"无":v;}
}
