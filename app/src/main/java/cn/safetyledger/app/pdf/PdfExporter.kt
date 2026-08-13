package cn.safetyledger.app.pdf

import android.graphics.*
import android.graphics.pdf.PdfDocument
import cn.safetyledger.app.data.InspectionEntity
import java.io.OutputStream
class PdfExporter { fun export(records:List<InspectionEntity>,out:OutputStream){val doc=PdfDocument();records.groupBy{it.date}.forEach{(_,group)->group.forEachIndexed{i,r->val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,doc.pages.size+1).create());val c=page.canvas;val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=14f};c.drawText("安全检查记录表",220f,45f,p.apply{textSize=20f});p.textSize=12f;var y=78f;listOf("日期时间：${r.date} ${r.time}","检查类型：${r.type}","被检查单位：${r.unit}","检查地点：${r.location}","值班人员：${r.dutyOfficer}","检查人员：${r.inspector1}、${r.inspector2}","被检查人：${r.inspectee}","检查结论：${r.conclusion}","整改意见：${r.rectificationAdvice}","责任人/期限：${r.responsiblePerson} / ${r.deadline}","整改情况：${r.rectificationDetail}","复查结果：${r.reviewResult}").forEach{c.drawText(it,40f,y,p);y+=28f};c.drawText("检查人员1签名：________  检查人员2签名：________  被检查人签名：________",40f,720f,p);c.drawText("第${i+1}页/共${group.size}页",250f,815f,p);doc.finishPage(page)}};doc.writeTo(out);doc.close()} }
