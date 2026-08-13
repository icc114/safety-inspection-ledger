package cn.safetyledger.app.pdf;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import cn.safetyledger.app.data.Entities.*;
import cn.safetyledger.app.data.LedgerRepository;
import java.io.*;
import java.time.LocalDate;
import java.util.*;

public final class PdfExporter {
    private static final int W=595,H=842,M=28;
    private final Context context; private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final LedgerRepository repo;
    public PdfExporter(Context c){context=c;repo=new LedgerRepository(c);p.setTypeface(Typeface.create("sans",Typeface.NORMAL));}
    private static final class PageSpec{Inspection record;int start;boolean table,lastTable;PageSpec(Inspection r,boolean t,int s,boolean last){record=r;table=t;start=s;lastTable=last;}}
    public void export(List<Inspection> records,OutputStream output)throws IOException{
        validateAssets(records);List<PageSpec> pages=new ArrayList<>();for(Inspection r:records){int chunks=Math.max(1,(r.items.size()+7)/8);for(int i=0;i<chunks;i++)pages.add(new PageSpec(r,true,i*8,i==chunks-1));for(int i=0;i<r.media.size();i+=4)pages.add(new PageSpec(r,false,i,false));}
        Map<String,Integer> totals=new HashMap<>();for(PageSpec s:pages)totals.put(s.record.date,totals.getOrDefault(s.record.date,0)+1);Map<String,Integer> nums=new HashMap<>();
        PdfDocument pdf=new PdfDocument();try{for(int i=0;i<pages.size();i++){PageSpec s=pages.get(i);int n=nums.getOrDefault(s.record.date,0)+1;nums.put(s.record.date,n);PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(W,H,i+1).create());Canvas c=page.getCanvas();c.drawColor(Color.WHITE);if(s.table)drawTable(c,s.record,s.start,s.lastTable);else drawPhotos(c,s.record,s.start);text(c,W/2f,H-16,s.record.date+"  第"+n+"页/共"+totals.get(s.record.date)+"页",10,Paint.Align.CENTER,false);pdf.finishPage(page);}pdf.writeTo(output);}finally{pdf.close();}}
    private void validateAssets(List<Inspection>records)throws IOException{for(Inspection r:records){for(Media m:r.media)if(m.localPath==null||m.localPath.isBlank()||!new File(m.localPath).isFile())throw new IOException("记录 "+r.date+" 缺少"+m.category+"文件；云端按需下载尚不可用，已停止导出");for(Signature s:repo.signatures(r.id))if(s.path==null||!new File(s.path).isFile())throw new IOException("记录 "+r.date+" 缺少"+s.role+"签名文件，已停止导出");}}
    private void drawTable(Canvas c,Inspection r,int start,boolean lastTable){float y=30;text(c,W/2f,y,r.templateName+"记录表",25,Paint.Align.CENTER,true);y=48;float[] cols={M,130,165,390,480,W-M};
        row(c,y,34,cols,new String[]{"检查时间",r.date+" "+r.time,"检查地点",r.location},new int[]{1,2,1,2});y+=34;
        row(c,y,32,cols,new String[]{"被检查单位",r.unit,"检查类型",r.type},new int[]{1,2,1,2});y+=32;
        row(c,y,32,cols,new String[]{"值班人员",r.onDuty,"检查人员",r.inspector1+"、"+r.inspector2},new int[]{1,2,1,2});y+=32;
        float[] tcols={M,122,155,380,472,W-M};row(c,y,34,tcols,new String[]{"检查类别","序号","检查内容及标准","检查结果","现场情况/问题"},new int[]{1,1,1,1,1});y+=34;
        int end=Math.min(start+8,r.items.size()),count=Math.max(1,end-start);float available=lastTable?365:570;float itemH=Math.min(64,available/count);float font=itemH<50?9:10;for(int index=start;index<end;index++){InspectionItem it=r.items.get(index);rect(c,tcols[0],y,tcols[1]-tcols[0],itemH);wrapped(c,it.category,tcols[0]+4,y+5,tcols[1]-tcols[0]-8,font+2,3);rect(c,tcols[1],y,tcols[2]-tcols[1],itemH);text(c,(tcols[1]+tcols[2])/2,y+itemH/2+4,""+it.order,12,Paint.Align.CENTER,false);rect(c,tcols[2],y,tcols[3]-tcols[2],itemH);wrapped(c,it.content+"\n标准："+it.standard,tcols[2]+4,y+4,tcols[3]-tcols[2]-8,font,6);rect(c,tcols[3],y,tcols[4]-tcols[3],itemH);wrapped(c,result(it.result),tcols[3]+5,y+5,tcols[4]-tcols[3]-10,font+1,3);rect(c,tcols[4],y,tcols[5]-tcols[4],itemH);wrapped(c,it.problem,tcols[4]+4,y+5,tcols[5]-tcols[4]-8,font,5);y+=itemH;}
        if(!lastTable){text(c,W/2f,y+24,"检查项目续下页",11,Paint.Align.CENTER,false);return;}
        float detailH=100;rect(c,M,y,W-2*M,detailH);wrapped(c,"检查结论："+r.conclusion+"\n整改意见："+r.advice+"\n责任人："+r.responsible+"    整改期限："+r.deadline+"\n整改情况："+r.rectification+"\n复查结果："+r.recheck,M+8,y+8,W-2*M-16,11,6);y+=detailH;
        float sigH=Math.min(90,H-y-45);float cw=(W-2*M)/3f;String[] roles={"INSPECTOR1","INSPECTOR2","INSPECTEE"},names={"检查人员1","检查人员2","被检查人"};List<Signature>sigs=repo.signatures(r.id);for(int i=0;i<3;i++){float x=M+i*cw;rect(c,x,y,cw,sigH);text(c,x+5,y+16,names[i]+"：",10,Paint.Align.LEFT,true);for(Signature s:sigs)if(roles[i].equals(s.role)){Bitmap b=BitmapFactory.decodeFile(s.path);if(b!=null){RectF fit=fit(b,x+8,y+20,cw-16,sigH-24);c.drawBitmap(b,null,fit,p);b.recycle();}}}}
    private void drawPhotos(Canvas c,Inspection r,int start){text(c,W/2f,30,r.templateName+" · 照片附件",22,Paint.Align.CENTER,true);text(c,M,52,r.date+"  "+r.location,11,Paint.Align.LEFT,false);for(int j=0;j<4&&start+j<r.media.size();j++){Media m=r.media.get(start+j);int col=j%2,row=j/2;float x=M+col*(W-2*M+10)/2f,y=70+row*350;float w=(W-2*M-10)/2f,h=300;rect(c,x,y,w,h);Bitmap b=m.localPath==null?null:BitmapFactory.decodeFile(m.localPath);if(b!=null){c.drawBitmap(b,null,fit(b,x+3,y+3,w-6,h-28),p);b.recycle();}String label=switch(m.category){case"PROBLEM"->"问题照片";case"RECTIFICATION"->"整改照片";case"RECHECK"->"复查照片";default->"现场照片";};text(c,x+4,y+h-8,label+"  "+m.location,10,Paint.Align.LEFT,false);}}
    private RectF fit(Bitmap b,float x,float y,float w,float h){float s=Math.min(w/b.getWidth(),h/b.getHeight());float nw=b.getWidth()*s,nh=b.getHeight()*s;return new RectF(x+(w-nw)/2,y+(h-nh)/2,x+(w+nw)/2,y+(h+nh)/2);}
    private String result(String r){return "PASS".equals(r)?"☑ 合格\n□ 不合格\n□ 不适用":"FAIL".equals(r)?"□ 合格\n☑ 不合格\n□ 不适用":"□ 合格\n□ 不合格\n☑ 不适用";}
    private void row(Canvas c,float y,float h,float[]xs,String[]values,int[]spans){int xi=0;for(int i=0;i<values.length;i++){int span=spans[i];float x=xs[xi],right=xs[Math.min(xs.length-1,xi+span)];rect(c,x,y,right-x,h);wrapped(c,values[i],x+5,y+8,right-x-10,12,3);xi+=span;}}
    private void rect(Canvas c,float x,float y,float w,float h){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.7f);p.setColor(Color.BLACK);c.drawRect(x,y,x+w,y+h,p);p.setStyle(Paint.Style.FILL);}
    private void text(Canvas c,float x,float y,String s,float size,Paint.Align a,boolean bold){p.setTextSize(size);p.setTextAlign(a);p.setColor(Color.BLACK);p.setTypeface(Typeface.create("sans",bold?Typeface.BOLD:Typeface.NORMAL));c.drawText(s==null?"":s,x,y,p);}
    private void wrapped(Canvas c,String s,float x,float y,float width,float size,int max){if(s==null)return;p.setTextSize(size);p.setTextAlign(Paint.Align.LEFT);p.setTypeface(Typeface.create("sans",Typeface.NORMAL));List<String>lines=new ArrayList<>();for(String para:s.split("\n",-1)){StringBuilder line=new StringBuilder();for(int off=0;off<para.length();){int cp=para.codePointAt(off);String ch=new String(Character.toChars(cp));if(p.measureText(line+ch)>width&&line.length()>0){lines.add(line.toString());line.setLength(0);}line.append(ch);off+=Character.charCount(cp);}lines.add(line.toString());}for(int i=0;i<Math.min(max,lines.size());i++)c.drawText(lines.get(i),x,y+size*(i+1)*1.25f,p);}
}
