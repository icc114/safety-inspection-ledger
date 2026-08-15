package cn.safetyledger.pc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Raster-first PDF renderer so Chinese text prints consistently on Windows without font embedding. */
public final class DesktopPdfExporter {
    private static final int W = 1654;
    private static final int H = 2339;
    private static final int M = 58;
    private static final Font BASE = chooseFont();
    private static final Color TEXT = new Color(25, 25, 25);
    private static final Color LINE = new Color(75, 75, 75);
    private static final Color PALE = new Color(242, 246, 252);
    private static final Color PROBLEM = new Color(255, 249, 240);
    private static final DateTimeFormatter PHOTO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DesktopPdfExporter() {}

    public static void export(List<Entry> entries, Path destination) throws Exception {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("没有可导出的检查记录");
        Files.createDirectories(destination.toAbsolutePath().getParent());
        try (PDDocument pdf = new PDDocument()) {
            for (Entry entry : entries) appendRecord(pdf, entry);
            pdf.save(destination.toFile());
        }
    }

    private static void appendRecord(PDDocument pdf, Entry entry) throws Exception {
        List<Photo> photos = photos(entry.folder);
        int totalPages = 1 + (photos.size() + 3) / 4;
        addImagePage(pdf, renderForm(entry, 1, totalPages));
        int index = 0;
        int page = 2;
        while (index < photos.size()) {
            List<Photo> current = photos.subList(index, Math.min(index + 4, photos.size()));
            addImagePage(pdf, renderPhotos(entry, current, page, totalPages));
            index += current.size();
            page++;
        }
    }

    private static void addImagePage(PDDocument pdf, BufferedImage image) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        pdf.addPage(page);
        PDImageXObject ximage = LosslessFactory.createFromImage(pdf, image);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.drawImage(ximage, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
        } finally {
            image.flush();
        }
    }

    private static BufferedImage renderForm(Entry entry, int page, int totalPages) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        quality(g);
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setColor(TEXT);

        ArchiveService.Record r = entry.record;
        int x = M, width = W - M * 2, y = 44;
        drawCentered(g, formTitle(r), x, y, width, 72, 38, true);
        y += 78;

        int basicH = 84;
        int[] basic = pxWidths(width, new int[]{1900, 3500, 2000, 3540}, 10940);
        drawRow(g, x, y, basic, basicH, new String[]{"检查时间：", displayDate(r), "检查地点：", blank(r.location)},
                new int[]{19, 19, 19, 19}, new boolean[]{true, false, true, false}, new int[]{0, 0, 0, 0}, null);
        y += basicH;

        int headerH = 70;
        int[] cols = pxWidths(width, new int[]{1760, 660, 4340, 1660, 2520}, 10940);
        drawRow(g, x, y, cols, headerH,
                new String[]{"检查类别", "序号", "检查内容及标准", "检查结果", "现场情况/问题"},
                new int[]{17,17,17,17,17}, new boolean[]{true,true,true,true,true}, new int[]{1,1,1,1,1}, PALE);
        y += headerH;

        int bodyH = 1260;
        if (r.items == null || r.items.isEmpty()) {
            g.setColor(Color.WHITE); g.fillRect(x, y, width, bodyH); g.setColor(LINE); g.drawRect(x, y, width, bodyH);
            drawCentered(g, "当前模板没有检查项目", x, y, width, bodyH, 20, false);
            y += bodyH;
        } else {
            FormLayout.Result layout = FormLayout.calculate(r);
            int used = 0;
            for (int i = 0; i < r.items.size(); i++) {
                ArchiveService.Item item = r.items.get(i);
                int h = i == r.items.size() - 1 ? bodyH - used
                        : Math.max(20, (int)Math.round(bodyH * (layout.rowHeightsTwips[i] / (double) FormLayout.ITEM_BODY_TWIPS)));
                if (used + h > bodyH) h = bodyH - used;
                String description = blank(item.content);
                if (item.standard != null && !item.standard.isBlank() && !item.standard.equals(item.content)) {
                    description += "\n标准：" + item.standard;
                }
                int font = Math.max(11, layout.itemFontSize + 5);
                drawRow(g, x, y + used, cols, h,
                        new String[]{blank(item.category), String.valueOf(item.order), description, result(item.result), blank(item.problem)},
                        new int[]{font, font, font, font, font}, new boolean[]{false,false,false,false,false},
                        new int[]{1,1,0,1,0}, "FAIL".equals(item.result) ? PROBLEM : Color.WHITE);
                used += h;
            }
            y += bodyH;
        }

        int summaryH = 108;
        int[] summaryCols = pxWidths(width, new int[]{1760,9180},10940);
        String opinion = problemOpinion(r);
        String rectification = rectificationText(r);
        drawRow(g, x, y, summaryCols, summaryH, new String[]{"整改意见：", opinion},
                new int[]{17, summaryFont(opinion)}, new boolean[]{true,false}, new int[]{0,0}, null);
        y += summaryH;
        drawRow(g, x, y, summaryCols, summaryH, new String[]{"整改记录：", rectification},
                new int[]{17, summaryFont(rectification)}, new boolean[]{true,false}, new int[]{0,0}, null);
        y += summaryH;

        int signH = 92;
        int[] signCols = pxWidths(width, new int[]{1760,3960,1800,3420},10940);
        drawSignatureArea(g, x, y, signCols, signH, entry.folder, r);
        y += signH * 2;

        g.setColor(new Color(100,100,100));
        g.setFont(BASE.deriveFont(Font.PLAIN, 14f));
        String footer = blank(r.date) + "  第" + page + "页/共" + totalPages + "页";
        drawCentered(g, footer, M, H - 52, W - M * 2, 28, 14, false);
        g.dispose();
        return image;
    }

    private static void drawSignatureArea(Graphics2D g, int x, int y, int[] widths, int rowH, Path folder, ArchiveService.Record record) {
        int total = sum(widths);
        g.setColor(Color.WHITE); g.fillRect(x, y, total, rowH * 2);
        g.setColor(LINE); g.drawRect(x, y, total, rowH * 2);
        int x1=x+widths[0], x2=x1+widths[1], x3=x2+widths[2];
        g.drawLine(x1,y,x1,y+rowH*2); g.drawLine(x2,y,x2,y+rowH*2); g.drawLine(x3,y,x3,y+rowH*2);
        g.drawLine(x1,y+rowH,x2,y+rowH);
        drawCellText(g,"检查人：",x,y,widths[0],rowH*2,17,true,0);
        drawCellText(g,"1.",x1,y,36,rowH,15,false,0);
        drawCellText(g,"2.",x1,y+rowH,36,rowH,15,false,0);
        drawCellText(g,"被检查人：",x2,y,widths[2],rowH*2,17,true,0);
        Path sigDir=folder.resolve("签名");
        drawSignature(g,findSignature(sigDir,"INSPECTOR1"),x1+40,y+8,widths[1]-48,rowH-16);
        drawSignature(g,findSignature(sigDir,"INSPECTOR2"),x1+40,y+rowH+8,widths[1]-48,rowH-16);
        drawSignature(g,findSignature(sigDir,"INSPECTEE"),x3+8,y+8,widths[3]-16,rowH*2-16);
    }

    private static void drawSignature(Graphics2D g, Path image, int x, int y, int w, int h) {
        if (image == null || !Files.isRegularFile(image)) return;
        try {
            BufferedImage src=ImageIO.read(image.toFile()); if(src==null)return;
            Rectangle fit=fit(src.getWidth(),src.getHeight(),x,y,w,h);
            g.drawImage(src,fit.x,fit.y,fit.width,fit.height,null); src.flush();
        } catch(Exception ignored){}
    }

    private static BufferedImage renderPhotos(Entry entry, List<Photo> photos, int page, int totalPages) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); quality(g);
        g.setColor(Color.WHITE); g.fillRect(0,0,W,H);
        drawCentered(g, formTitle(entry.record) + " · 照片附件", M, 42, W-M*2, 62, 28, true);
        int gap=28, top=125, usableW=W-M*2, cellW=(usableW-gap)/2, cellH=980;
        for(int i=0;i<photos.size();i++){
            int row=i/2,col=i%2;int x=M+col*(cellW+gap),y=top+row*(cellH+gap);
            g.setColor(new Color(245,245,245));g.fillRect(x,y,cellW,cellH);g.setColor(LINE);g.drawRect(x,y,cellW,cellH);
            Photo photo=photos.get(i);
            try{
                BufferedImage src=ImageIO.read(photo.path.toFile());
                if(src!=null){Rectangle fit=fit(src.getWidth(),src.getHeight(),x+14,y+14,cellW-28,cellH-92);g.drawImage(src,fit.x,fit.y,fit.width,fit.height,null);src.flush();}
            }catch(Exception ignored){}
            g.setColor(TEXT);g.setFont(BASE.deriveFont(Font.PLAIN,15f));
            String caption=photo.category+" · "+photo.path.getFileName();
            drawCentered(g,caption,x+10,y+cellH-68,cellW-20,28,15,false);
        }
        drawCentered(g, blank(entry.record.date)+"  第"+page+"页/共"+totalPages+"页", M,H-52,W-M*2,28,14,false);
        g.dispose(); return image;
    }

    private static void drawRow(Graphics2D g, int x, int y, int[] widths, int h, String[] texts, int[] sizes,
                                boolean[] bold, int[] align, Color fill) {
        int cx=x;
        for(int i=0;i<widths.length;i++){
            Color bg=fill==null?Color.WHITE:fill;g.setColor(bg);g.fillRect(cx,y,widths[i],h);g.setColor(LINE);g.drawRect(cx,y,widths[i],h);
            drawCellText(g,texts[i],cx,y,widths[i],h,sizes[i],bold[i],align[i]);cx+=widths[i];
        }
    }

    private static void drawCellText(Graphics2D g,String text,int x,int y,int w,int h,int size,boolean bold,int align){
        g.setColor(TEXT);g.setFont(BASE.deriveFont(bold?Font.BOLD:Font.PLAIN,(float)size));
        FontMetrics fm=g.getFontMetrics();List<String> lines=wrap(blank(text),fm,Math.max(20,w-16));
        int lineH=Math.max(fm.getHeight(),size+5);int total=lines.size()*lineH;int yy=y+Math.max(lineH,(h-total)/2+fm.getAscent());
        int maxLines=Math.max(1,(h-8)/Math.max(1,lineH));
        for(int i=0;i<lines.size()&&i<maxLines;i++){
            String line=lines.get(i);int tw=fm.stringWidth(line);int xx=align==1?x+(w-tw)/2:x+8;
            g.drawString(line,Math.max(x+5,xx),yy);yy+=lineH;
        }
    }

    private static List<String> wrap(String text,FontMetrics fm,int width){
        List<String> out=new ArrayList<>();
        if(text==null||text.isEmpty()){out.add("");return out;}
        for(String logical:text.split("\\n",-1)){
            if(logical.isEmpty()){out.add("");continue;}
            StringBuilder line=new StringBuilder();
            for(int offset=0;offset<logical.length();){
                int cp=logical.codePointAt(offset);String part=new String(Character.toChars(cp));offset+=Character.charCount(cp);
                if(line.length()>0&&fm.stringWidth(line+part)>width){out.add(line.toString());line.setLength(0);}
                line.append(part);
            }
            if(line.length()>0)out.add(line.toString());
        }
        return out.isEmpty()?List.of(""):out;
    }

    private static void drawCentered(Graphics2D g,String text,int x,int y,int w,int h,int size,boolean bold){
        g.setColor(TEXT);g.setFont(BASE.deriveFont(bold?Font.BOLD:Font.PLAIN,(float)size));FontMetrics fm=g.getFontMetrics();
        int tw=fm.stringWidth(blank(text));int xx=x+(w-tw)/2;int yy=y+(h-fm.getHeight())/2+fm.getAscent();g.drawString(blank(text),Math.max(x,xx),yy);
    }

    private static int[] pxWidths(int total,int[] weights,int denominator){int[] out=new int[weights.length];int used=0;for(int i=0;i<weights.length;i++){out[i]=i==weights.length-1?total-used:(int)Math.round(total*(weights[i]/(double)denominator));used+=out[i];}return out;}
    private static int sum(int[] v){int n=0;for(int x:v)n+=x;return n;}
    private static Rectangle fit(int sw,int sh,int x,int y,int w,int h){double ratio=Math.min(w/(double)Math.max(1,sw),h/(double)Math.max(1,sh));int dw=Math.max(1,(int)Math.round(sw*ratio)),dh=Math.max(1,(int)Math.round(sh*ratio));return new Rectangle(x+(w-dw)/2,y+(h-dh)/2,dw,dh);}
    private static void quality(Graphics2D g){g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);}

    private static Font chooseFont(){String[] names={"Microsoft YaHei","微软雅黑","SimHei","黑体","SansSerif"};GraphicsEnvironment env=GraphicsEnvironment.getLocalGraphicsEnvironment();List<String> available=List.of(env.getAvailableFontFamilyNames());for(String n:names)if(available.contains(n))return new Font(n,Font.PLAIN,18);return new Font(Font.SANS_SERIF,Font.PLAIN,18);}
    private static Path findSignature(Path dir,String role){if(!Files.isDirectory(dir))return null;try(var s=Files.list(dir)){return s.filter(Files::isRegularFile).filter(p->p.getFileName().toString().toUpperCase(Locale.ROOT).startsWith(role+".")).findFirst().orElse(null);}catch(Exception e){return null;}}
    private static List<Photo> photos(Path folder){List<Photo> out=new ArrayList<>();addPhotos(out,folder.resolve("检查照片"),"检查照片");addPhotos(out,folder.resolve("整改照片"),"整改照片");addPhotos(out,folder.resolve("复查照片"),"复查照片");return out;}
    private static void addPhotos(List<Photo> out,Path dir,String category){if(!Files.isDirectory(dir))return;try(var s=Files.list(dir)){s.filter(Files::isRegularFile).filter(DesktopPdfExporter::isImage).sorted(Comparator.comparing(p->p.getFileName().toString())).forEach(p->out.add(new Photo(p,category)));}catch(Exception ignored){}}
    private static boolean isImage(Path p){String n=p.getFileName().toString().toLowerCase(Locale.ROOT);return n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp");}
    private static String problemOpinion(ArchiveService.Record r){List<String> p=new ArrayList<>();if(r.items!=null)for(ArchiveService.Item i:r.items)if("FAIL".equals(i.result))p.add(i.order+". "+(blank(i.problem).isBlank()?"需整改":i.problem.trim()));return p.isEmpty()?"无":String.join("；",p);}
    private static String rectificationText(ArchiveService.Record r){String detail=blank(r.rectification);String status=status(r.status);return detail.isBlank()?status:detail+"（"+status+"）";}
    private static int summaryFont(String text){int n=blank(text).length();return n>100?12:n>65?13:15;}
    private static String displayDate(ArchiveService.Record r){String d=blank(r.date),t=blank(r.time);return t.isBlank()?d:d+" "+t;}
    private static String result(String v){return "PASS".equals(v)?"是":"FAIL".equals(v)?"否":"未填写";}
    private static String status(String v){if(v==null)return"";return switch(v){case"PENDING_RECTIFICATION"->"待整改";case"RECTIFYING"->"整改中";case"RECTIFIED"->"已整改完成";case"COMPLETED"->"检查完成";case"DRAFT"->"草稿";default->v;};}
    private static String formTitle(ArchiveService.Record r){String n=blank(r==null?null:r.templateName);if(n.isBlank())n="安全检查";if(n.endsWith("记录表"))return n;if(n.endsWith("记录"))return n+"表";return n+"记录表";}
    private static String blank(String v){return v==null?"":v;}

    public static final class Entry { public final ArchiveService.Record record; public final Path folder; public Entry(ArchiveService.Record record,Path folder){this.record=record;this.folder=folder;} }
    private record Photo(Path path,String category){}
}
