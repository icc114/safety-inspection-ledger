package cn.safetyledger.app.pdf

import android.graphics.*
import android.graphics.pdf.PdfDocument
import cn.safetyledger.app.data.*
import java.io.OutputStream

data class PrintableInspection(val record: InspectionEntity, val items: List<InspectionItemEntity>, val media: List<MediaEntity> = emptyList())

/** A4 formal inspection sheet. All headings and rows are driven by stored templates. */
class PdfExporter {
    private val pageWidth = 595
    private val pageHeight = 842
    private val left = 24f
    private val right = 571f
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = .7f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL; textSize = 9.5f; typeface = Typeface.create("sans", Typeface.NORMAL) }

    fun export(records: List<PrintableInspection>, output: OutputStream) {
        val document = PdfDocument()
        records.groupBy { it.record.date }.forEach { (_, sameDate) ->
            sameDate.forEachIndexed { index, printable -> drawRecordPage(document, printable, index + 1, sameDate.size) }
        }
        document.writeTo(output); document.close()
    }

    private fun drawRecordPage(doc: PdfDocument, data: PrintableInspection, pageNo: Int, pageCount: Int) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, doc.pages.size + 1).create())
        val c = page.canvas; val r = data.record
        centered(c, titleFor(r), 297.5f, 46f, 25f, true)
        var y = 65f
        row(c, y, 45f, floatArrayOf(left, 125f, 278f, 376f, right))
        label(c, "检查时间：", 31f, y + 27); value(c, dateZh(r.date), 143f, y + 27)
        label(c, "检查地点：", 286f, y + 27); value(c, r.location, 382f, y + 20, 180f)
        y += 45f
        val headerH = 37f; row(c, y, headerH, floatArrayOf(left, 125f, 158f, 366f, 444f, right), Color.rgb(218,228,245))
        centered(c,"检查类别",74f,y+23,11f); centered(c,"序号",141f,y+23,11f); centered(c,"检查内容及标准",262f,y+23,11f); centered(c,"检查结果",405f,y+23,11f); centered(c,"现场情况/问题",507f,y+23,10.5f)
        y += headerH
        val availableBottom=602f; val items=data.items.ifEmpty { listOf(InspectionItemEntity("","","","未设置项目","请在基础设置中维护检查项目","",ItemResult.NA)) }
        val itemH=((availableBottom-y)/items.size.coerceAtLeast(1)).coerceIn(39f,62f)
        items.forEachIndexed { i,item ->
            row(c,y,itemH,floatArrayOf(left,125f,158f,366f,444f,right))
            centered(c,item.category,74f,y+itemH/2+3,10f); centered(c,"${i+1}",141f,y+itemH/2+3,10f)
            wrapped(c, listOf(item.content,item.standard).filter{it.isNotBlank()}.joinToString("；"), 162f,y+13,198f,10f,3)
            val yes=if(item.result==ItemResult.PASS) "☑ 是" else "□ 是"; val no=if(item.result==ItemResult.FAIL) "☑ 否" else "□ 否"
            value(c,yes,378f,y+20); value(c,no,378f,y+37); wrapped(c,item.problem,449f,y+15,116f,9f,3); y+=itemH
        }
        val detailH=125f; row(c,y,detailH,floatArrayOf(left,125f,right)); label(c,"检查情况：",35f,y+20); wrapped(c,r.conclusion,132f,y+20,430f,10f,4); label(c,"整改意见：",132f,y+62); wrapped(c,r.rectificationAdvice,205f,y+62,350f,10f,4); y+=detailH
        val signH=65f; row(c,y,signH,floatArrayOf(left,125f,278f,376f,right)); label(c,"检查人：",45f,y+37); value(c,"1. ${r.inspector1}",132f,y+25); value(c,"2. ${r.inspector2}",132f,y+50); label(c,"被检查人：",289f,y+37); value(c,r.inspectee,390f,y+37)
        value(c,"第${pageNo}页/共${pageCount}页",262f,820f); doc.finishPage(page)
    }

    private fun titleFor(r:InspectionEntity)=when { r.type.endsWith("检查")->r.type+"记录表"; r.type.isNotBlank()->r.type+"检查记录表"; else->"安全检查记录表" }
    private fun dateZh(s:String)=s.split("-").let{if(it.size==3)"${it[0]}年${it[1].toInt()}月${it[2].toInt()}日" else s}
    private fun row(c:Canvas,y:Float,h:Float,x:FloatArray,fill:Int?=null){fill?.let{c.drawRect(x.first(),y,x.last(),y+h,Paint().apply{color=it;style=Paint.Style.FILL})};c.drawRect(x.first(),y,x.last(),y+h,ink);x.drop(1).dropLast(1).forEach{c.drawLine(it,y,it,y+h,ink)}}
    private fun label(c:Canvas,s:String,x:Float,y:Float)=value(c,s,x,y,true)
    private fun value(c:Canvas,s:String,x:Float,y:Float,bold:Boolean=false){text.typeface=Typeface.create("sans",if(bold)Typeface.BOLD else Typeface.NORMAL);text.textSize=10f;c.drawText(s,x,y,text)}
    private fun value(c:Canvas,s:String,x:Float,y:Float,width:Float){wrapped(c,s,x,y,width,9.5f,2)}
    private fun centered(c:Canvas,s:String,x:Float,y:Float,size:Float,bold:Boolean=false){text.textSize=size;text.typeface=Typeface.create("sans",if(bold)Typeface.BOLD else Typeface.NORMAL);c.drawText(s,x-text.measureText(s)/2,y,text)}
    private fun wrapped(c:Canvas,s:String,x:Float,y:Float,width:Float,size:Float,max:Int){text.textSize=size;text.typeface=Typeface.create("sans",Typeface.NORMAL);var line="";var yy=y;var count=0;for(ch in s){if(text.measureText(line+ch)>width){c.drawText(line,x,yy,text);line="$ch";yy+=size+3;if(++count>=max-1)break}else line+=ch};if(line.isNotEmpty())c.drawText(line,x,yy,text)}
}
