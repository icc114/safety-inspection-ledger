package cn.safetyledger.pc;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** High-contrast supersampled runtime icon matching the mobile blue document/star/shield identity. */
public final class AppIcon {
    private AppIcon() {}

    public static Image image(int size) {
        int target = Math.max(24, size);
        int source = Math.max(128, target * 4);
        BufferedImage hi = new BufferedImage(source, source, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hi.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double k = source / 108.0;
            g.scale(k, k);

            // White edge keeps the icon readable on the blue desktop header.
            g.setColor(new Color(255, 255, 255, 245));
            g.fillRoundRect(2, 2, 104, 104, 29, 29);
            g.setColor(new Color(20, 111, 238));
            g.fillRoundRect(6, 6, 96, 96, 25, 25);

            // Simple directional shading, deliberately bold enough to survive 32–48 px rendering.
            GradientPaint gradient = new GradientPaint(12, 8, new Color(54, 139, 255), 96, 100, new Color(7, 74, 190));
            g.setPaint(gradient);
            g.fillRoundRect(8, 8, 92, 92, 23, 23);

            g.setColor(new Color(5, 49, 142, 70));
            Path2D shadow = new Path2D.Double();
            shadow.moveTo(27, 84); shadow.lineTo(77, 33); shadow.lineTo(98, 53);
            shadow.lineTo(98, 82); shadow.curveTo(98, 92, 92, 98, 82, 98);
            shadow.lineTo(42, 98); shadow.closePath(); g.fill(shadow);

            g.setColor(Color.WHITE);
            Path2D paper = new Path2D.Double();
            paper.moveTo(25, 21); paper.lineTo(62, 21); paper.lineTo(77, 36); paper.lineTo(77, 78);
            paper.curveTo(77, 82, 74, 85, 70, 85); paper.lineTo(25, 85); paper.curveTo(21, 85, 18, 82, 18, 78);
            paper.lineTo(18, 28); paper.curveTo(18, 24, 21, 21, 25, 21); paper.closePath(); g.fill(paper);

            g.setColor(new Color(226, 239, 255));
            Path2D fold = new Path2D.Double();
            fold.moveTo(62, 21); fold.lineTo(77, 36); fold.lineTo(67, 36); fold.curveTo(64, 36, 62, 34, 62, 31); fold.closePath(); g.fill(fold);

            g.setColor(new Color(13, 94, 216));
            g.fillRoundRect(28, 34, 34, 6, 5, 5);
            g.fillRoundRect(40, 49, 24, 6, 5, 5);
            g.fillRoundRect(40, 64, 20, 6, 5, 5);
            drawCheck(g, 24, 44, 12, new Color(13, 94, 216));
            drawCheck(g, 24, 59, 12, new Color(13, 94, 216));
            drawStar(g, 67, 53, 9, new Color(255, 177, 0));

            // Shield is slightly oversized so it remains obvious in the Windows title bar.
            g.setColor(Color.WHITE);
            g.fill(shield(80, 77, 20, 24));
            g.setColor(new Color(7, 82, 203));
            g.fill(shield(80, 77, 16, 19));
            drawCheck(g, 71, 70, 16, Color.WHITE);
        } finally {
            g.dispose();
        }

        BufferedImage out = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D down = out.createGraphics();
        try {
            down.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            down.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            down.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            down.drawImage(hi, 0, 0, target, target, null);
        } finally {
            down.dispose();
        }
        return out;
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
        g.setColor(color);
        g.setStroke(new BasicStroke(Math.max(3f, size / 4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
        p.closePath();
        g.setColor(color);
        g.fill(p);
    }
}
