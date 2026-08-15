package cn.safetyledger.pc;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** Vector-style runtime icon matching the blue document/star/shield app identity. */
public final class AppIcon {
    private AppIcon() {}

    public static Image image(int size) {
        int s = Math.max(32, size);
        BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double k = s / 108.0;
            g.scale(k, k);

            g.setColor(new Color(20, 118, 242));
            g.fillRoundRect(6, 6, 96, 96, 26, 26);
            g.setColor(new Color(8, 69, 173, 80));
            Path2D shadow = new Path2D.Double();
            shadow.moveTo(20, 89); shadow.lineTo(91, 18); shadow.lineTo(102, 29);
            shadow.lineTo(102, 84); shadow.curveTo(102, 95, 95, 102, 84, 102);
            shadow.lineTo(31, 102); shadow.closePath(); g.fill(shadow);

            g.setColor(Color.WHITE);
            Path2D paper = new Path2D.Double();
            paper.moveTo(25,21); paper.lineTo(63,21); paper.lineTo(77,36); paper.lineTo(77,78);
            paper.curveTo(77,81,75,83,72,83); paper.lineTo(25,83); paper.curveTo(22,83,20,81,20,78);
            paper.lineTo(20,26); paper.curveTo(20,23,22,21,25,21); paper.closePath(); g.fill(paper);
            g.setColor(new Color(234,243,255));
            Path2D fold = new Path2D.Double(); fold.moveTo(63,21); fold.lineTo(77,36); fold.lineTo(66,36); fold.curveTo(64,36,63,34,63,32); fold.closePath(); g.fill(fold);

            g.setColor(new Color(18,102,218));
            g.fillRoundRect(28,36,32,6,4,4); g.fillRoundRect(28,50,24,6,4,4); g.fillRoundRect(40,64,19,6,4,4);
            drawCheck(g, 24, 44, 18, new Color(18,102,218));
            drawCheck(g, 24, 58, 18, new Color(18,102,218));
            drawStar(g, 66, 55, 10, new Color(255,177,0));

            g.setColor(Color.WHITE);
            Path2D shieldOuter = shield(80,76,20,25); g.fill(shieldOuter);
            g.setColor(new Color(11,94,215));
            Path2D shieldInner = shield(80,76,16,20); g.fill(shieldInner);
            drawCheck(g, 70, 68, 21, Color.WHITE);
        } finally {
            g.dispose();
        }
        return image;
    }

    public static Icon icon(int size) { return new ImageIcon(image(size)); }

    private static Path2D shield(double cx, double cy, double w, double h) {
        Path2D p = new Path2D.Double();
        p.moveTo(cx - w, cy - h * .55); p.lineTo(cx + 3, cy - h); p.lineTo(cx + w, cy - h * .55);
        p.lineTo(cx + w, cy + h * .18); p.curveTo(cx + w, cy + h * .65, cx + 9, cy + h * .92, cx, cy + h);
        p.curveTo(cx - 9, cy + h * .92, cx - w, cy + h * .65, cx - w, cy + h * .18); p.closePath();
        return p;
    }

    private static void drawCheck(Graphics2D g, int x, int y, int size, Color color) {
        Stroke old = g.getStroke();
        g.setColor(color); g.setStroke(new BasicStroke(Math.max(3f, size / 4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x, y + size / 2, x + size / 3, y + size * 4 / 5);
        g.drawLine(x + size / 3, y + size * 4 / 5, x + size, y);
        g.setStroke(old);
    }

    private static void drawStar(Graphics2D g, double cx, double cy, double r, Color color) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double rr = (i % 2 == 0) ? r : r * .45;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            double x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath(); g.setColor(color); g.fill(p);
    }
}
