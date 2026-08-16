package cn.safetyledger.pc;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Crisp multi-size vector icon. Shapes are intentionally simple so 16–64 px Windows rendering stays sharp. */
public final class AppIcon {
    private AppIcon() {}

    public static Image image(int size) {
        int target=Math.max(16,size), source=Math.max(512,target*8);
        BufferedImage hi=new BufferedImage(source,source,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=hi.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
            double k=source/100.0;g.scale(k,k);
            g.setColor(new Color(255,255,255,250));g.fill(new RoundRectangle2D.Double(1.5,1.5,97,97,21,21));
            g.setColor(new Color(20,105,224));g.fill(new RoundRectangle2D.Double(5,5,90,90,18,18));

            // document
            g.setColor(Color.WHITE);
            Path2D paper=new Path2D.Double();paper.moveTo(24,18);paper.lineTo(59,18);paper.lineTo(73,32);paper.lineTo(73,78);
            paper.curveTo(73,82,70,85,66,85);paper.lineTo(24,85);paper.curveTo(20,85,17,82,17,78);paper.lineTo(17,25);paper.curveTo(17,21,20,18,24,18);paper.closePath();g.fill(paper);
            g.setColor(new Color(216,234,255));Path2D fold=new Path2D.Double();fold.moveTo(59,18);fold.lineTo(73,32);fold.lineTo(64,32);fold.curveTo(61,32,59,30,59,27);fold.closePath();g.fill(fold);

            g.setStroke(new BasicStroke(4.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.setColor(new Color(20,105,224));
            check(g,25,39);check(g,25,56);check(g,25,73);
            g.setStroke(new BasicStroke(4.0f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawLine(41,39,61,39);g.drawLine(41,56,61,56);g.drawLine(41,73,55,73);

            // bottom-right shield
            Path2D shield=new Path2D.Double();shield.moveTo(75,55);shield.lineTo(92,61);shield.lineTo(92,73);shield.curveTo(92,84,85,91,75,95);shield.curveTo(65,91,58,84,58,73);shield.lineTo(58,61);shield.closePath();
            g.setColor(new Color(255,255,255));g.setStroke(new BasicStroke(6f,BasicStroke.JOIN_ROUND,BasicStroke.CAP_ROUND));g.draw(shield);
            g.setColor(new Color(255,178,25));g.fill(shield);
            g.setColor(Color.WHITE);g.setStroke(new BasicStroke(4.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawLine(66,74,72,80);g.drawLine(72,80,84,68);
        }finally{g.dispose();}
        BufferedImage out=new BufferedImage(target,target,BufferedImage.TYPE_INT_ARGB);Graphics2D d=out.createGraphics();
        try{d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);d.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);d.drawImage(hi,0,0,target,target,null);}finally{d.dispose();}
        return out;
    }
    public static Icon icon(int size){return new ImageIcon(image(size));}
    private static void check(Graphics2D g,int x,int y){g.drawLine(x,y,x+4,y+4);g.drawLine(x+4,y+4,x+10,y-5);}
}
