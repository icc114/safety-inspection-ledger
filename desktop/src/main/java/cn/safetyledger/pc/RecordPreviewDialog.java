package cn.safetyledger.pc;

import com.google.gson.Gson;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Modern read-only preview matching the desktop dashboard visual language. */
public final class RecordPreviewDialog extends JDialog {
    private static final Gson GSON = new Gson();
    private static final Color BLUE = new Color(27, 103, 219);
    private static final Color BLUE_PALE = new Color(237, 245, 255);
    private static final Color LINE = new Color(218, 228, 242);
    private final Path folder;
    private final ArchiveService.Record record;
    private final CardLayout pages = new CardLayout();
    private final JPanel content = new JPanel(pages);

    private RecordPreviewDialog(Window owner, Path folder, ArchiveService.Record record) {
        super(owner, "安全检查台账 · 检查记录预览", ModalityType.APPLICATION_MODAL);
        this.folder = folder;
        this.record = record;
        setIconImage(AppIcon.image(64));
        buildUi();
        setSize(1220, 760);
        setMinimumSize(new Dimension(980, 650));
        setLocationRelativeTo(owner);
    }

    public static void open(Window owner, Path folder) {
        try {
            Path json = folder.resolve("record.json");
            if (!Files.isRegularFile(json)) throw new IOException("该记录缺少 record.json，请重新同步一次检查内容");
            ArchiveService.Record record = GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), ArchiveService.Record.class);
            if (record == null) throw new IOException("记录预览数据为空");
            new RecordPreviewDialog(owner, folder, record).setVisible(true);
        } catch (Exception error) {
            JOptionPane.showMessageDialog(owner, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                    "无法预览检查记录", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void buildUi() {
        getContentPane().setBackground(new Color(247, 249, 252));
        setLayout(new BorderLayout(0, 0));
        add(headerPanel(), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(12, 0));
        center.setBorder(new EmptyBorder(10, 14, 10, 14));
        center.setOpaque(false);
        center.add(sidebar(), BorderLayout.WEST);
        content.setBorder(BorderFactory.createLineBorder(LINE));
        content.add(itemsPanel(), "items");
        content.add(detailPanel(), "detail");
        content.add(photoPanel(), "photos");
        center.add(content, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        add(actionsPanel(), BorderLayout.SOUTH);
        pages.show(content, "items");
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private JComponent headerPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(16, 0));
        wrapper.setBackground(Color.WHITE);
        wrapper.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                new EmptyBorder(14, 20, 14, 20)));
        JLabel icon = new JLabel(AppIcon.icon(84));
        icon.setBorder(new EmptyBorder(0, 8, 0, 16));
        wrapper.add(icon, BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout(8, 10));
        right.setOpaque(false);
        JLabel title = new JLabel(formTitle(record.templateName));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        right.add(title, BorderLayout.NORTH);
        JPanel summary = new JPanel(new GridLayout(3, 4, 18, 8));
        summary.setOpaque(false);
        addInfo(summary, "▣", "检查日期", record.date, null);
        addInfo(summary, "◷", "检查时间", record.time, null);
        addInfo(summary, "▤", "检查类型", record.type, null);
        addInfo(summary, "⚑", "状态", status(record.status), statusColor(record.status));
        addInfo(summary, "⌖", "检查地点", record.location, null);
        addInfo(summary, "▧", "记录编号", shortId(record.id), null);
        right.add(summary, BorderLayout.CENTER);
        wrapper.add(right, BorderLayout.CENTER);
        return wrapper;
    }

    private JComponent sidebar() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(155, 0));
        side.setBackground(Color.WHITE);
        side.setBorder(BorderFactory.createLineBorder(LINE));
        side.add(navButton("▤  检查项目", "items", true));
        side.add(navButton("↻  整改 / 复查", "detail", false));
        side.add(navButton("▣  现场照片", "photos", false));
        side.add(Box.createVerticalGlue());
        return side;
    }

    private JButton navButton(String text, String key, boolean first) {
        JButton button = new JButton(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(14, 16, 14, 8));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 15f));
        button.setForeground(first ? BLUE : new Color(32, 42, 56));
        button.setBackground(first ? BLUE_PALE : Color.WHITE);
        button.setOpaque(true);
        button.addActionListener(e -> {
            pages.show(content, key);
            Container p = button.getParent();
            for (Component child : p.getComponents()) if (child instanceof JButton b) {
                boolean selected = b == button;
                b.setBackground(selected ? BLUE_PALE : Color.WHITE);
                b.setForeground(selected ? BLUE : new Color(32, 42, 56));
            }
        });
        return button;
    }

    private JPanel itemsPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(8, 10, 10, 10));
        JLabel heading = new JLabel("检查项目");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16f));
        heading.setForeground(new Color(20, 57, 105));
        heading.setBorder(new EmptyBorder(0, 4, 7, 0));
        panel.add(heading, BorderLayout.NORTH);
        DefaultTableModel model = new DefaultTableModel(new Object[]{"序号", "检查类别", "检查内容及标准", "结果", "现场问题"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        if (record.items != null) for (ArchiveService.Item item : record.items) {
            String itemText = blank(item.content, "");
            if (item.standard != null && !item.standard.isBlank() && !item.standard.equals(item.content)) itemText += "  标准：" + item.standard;
            model.addRow(new Object[]{item.order, blank(item.category, ""), itemText, result(item.result), blank(item.problem, "")});
        }
        JTable table = new JTable(model);
        table.setRowHeight(34);
        table.setShowVerticalLines(true);
        table.setGridColor(LINE);
        table.getTableHeader().setPreferredSize(new Dimension(10, 36));
        table.getTableHeader().setBackground(new Color(245, 249, 255));
        table.getTableHeader().setForeground(new Color(20, 57, 105));
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(560);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(230);
        table.getColumnModel().getColumn(2).setCellRenderer(new WrapRenderer());
        table.getColumnModel().getColumn(4).setCellRenderer(new WrapRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new ResultRenderer());
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel detailPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 10, 10));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.add(textSection("整改记录", record.rectification));
        panel.add(textSection("复查说明", record.recheck));
        return panel;
    }

    private JPanel textSection(String title, String value) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setForeground(new Color(20, 57, 105));
        JTextArea text = new JTextArea(blank(value, "无"));
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setMargin(new Insets(10,10,10,10));
        panel.add(label, BorderLayout.NORTH); panel.add(new JScrollPane(text), BorderLayout.CENTER); return panel;
    }

    private JComponent photoPanel() {
        JPanel root = new JPanel();
        root.setBackground(Color.WHITE);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        addPhotoSection(root, "检查照片", folder.resolve("检查照片"));
        addPhotoSection(root, "整改照片", folder.resolve("整改照片"));
        addPhotoSection(root, "复查照片", folder.resolve("复查照片"));
        JScrollPane scroll = new JScrollPane(root); scroll.getVerticalScrollBar().setUnitIncrement(18); return scroll;
    }

    private void addPhotoSection(JPanel root, String title, Path dir) {
        List<Path> files = imageFiles(dir);
        JLabel heading = new JLabel(title + "（" + files.size() + " 张）");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f)); heading.setBorder(new EmptyBorder(6,0,5,0)); heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(heading);
        JPanel gallery = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8)); gallery.setBackground(Color.WHITE); gallery.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (files.isEmpty()) gallery.add(new JLabel("无")); else for (Path file : files) gallery.add(photoCard(file));
        root.add(gallery);
    }

    private JComponent photoCard(Path file) {
        JButton button = new JButton(); button.setPreferredSize(new Dimension(190,155));
        button.setVerticalTextPosition(SwingConstants.BOTTOM); button.setHorizontalTextPosition(SwingConstants.CENTER); button.setText(file.getFileName().toString());
        try { BufferedImage image=ImageIO.read(file.toFile()); if(image!=null){int[] size=fit(image.getWidth(),image.getHeight(),170,112);button.setIcon(new ImageIcon(image.getScaledInstance(size[0],size[1],Image.SCALE_SMOOTH)));}} catch(Exception ignored){}
        button.addActionListener(e -> openImagePreview(file)); return button;
    }

    private JComponent actionsPanel() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 9));
        actions.setBackground(Color.WHITE);
        actions.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE));
        JButton word = new JButton("▣  打开 Word"); word.addActionListener(e -> openFile(folder.resolve("检查记录.docx"), "找不到检查记录.docx"));
        JButton openFolder = new JButton("▣  打开本地文件夹"); openFolder.addActionListener(e -> openFile(folder, "找不到本地记录文件夹"));
        JButton pdf = new JButton("PDF  导出 PDF"); pdf.addActionListener(e -> exportPdf());
        JButton close = new JButton("×  关闭"); close.setBackground(BLUE); close.setForeground(Color.WHITE); close.addActionListener(e -> dispose());
        for (JButton b : new JButton[]{word, openFolder, pdf, close}) { b.setFocusPainted(false); b.setMargin(new Insets(7,14,7,14)); }
        actions.add(word); actions.add(openFolder); actions.add(pdf); actions.add(close); getRootPane().setDefaultButton(close); return actions;
    }

    private void exportPdf() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File(record.date + "-" + formTitle(record.templateName) + "-" + shortId(record.id) + ".pdf"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = chooser.getSelectedFile().toPath();
        if (!out.getFileName().toString().toLowerCase().endsWith(".pdf")) out = out.resolveSibling(out.getFileName() + ".pdf");
        try {
            DesktopPdfExporter.export(List.of(new DesktopPdfExporter.Entry(record, folder)), out);
            JOptionPane.showMessageDialog(this, "PDF 已导出：\n" + out, "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception error) { JOptionPane.showMessageDialog(this, "PDF 导出失败：" + error.getMessage(), "导出失败", JOptionPane.ERROR_MESSAGE); }
    }

    private static void addInfo(JPanel panel, String icon, String label, String value, Color badge) {
        JLabel left = new JLabel(icon + "  " + label + "："); left.setForeground(BLUE); left.setFont(left.getFont().deriveFont(Font.BOLD)); panel.add(left);
        JLabel right = new JLabel(blank(value, "—"));
        if (badge != null) { right.setOpaque(true); right.setBackground(badge); right.setForeground(new Color(23, 120, 64)); right.setBorder(new EmptyBorder(3,8,3,8)); }
        panel.add(right);
    }

    private static Color statusColor(String status) {
        if ("RECTIFIED".equals(status) || "COMPLETED".equals(status)) return new Color(229, 248, 236);
        return null;
    }

    private void openImagePreview(Path file) {
        try { BufferedImage image=ImageIO.read(file.toFile()); if(image==null)throw new IOException("无法读取图片"); Dimension screen=Toolkit.getDefaultToolkit().getScreenSize(); int[] size=fit(image.getWidth(),image.getHeight(),Math.min(1100,screen.width-160),Math.min(760,screen.height-200)); JLabel label=new JLabel(new ImageIcon(image.getScaledInstance(size[0],size[1],Image.SCALE_SMOOTH))); JOptionPane.showMessageDialog(this,new JScrollPane(label),file.getFileName().toString(),JOptionPane.PLAIN_MESSAGE); }
        catch(Exception error){JOptionPane.showMessageDialog(this,"图片预览失败："+error.getMessage(),"提示",JOptionPane.ERROR_MESSAGE);}
    }

    private void openFile(Path path, String missing) { try { if(!Files.exists(path))throw new IOException(missing); Desktop.getDesktop().open(path.toFile()); } catch(Exception error){JOptionPane.showMessageDialog(this,error.getMessage(),"打开失败",JOptionPane.ERROR_MESSAGE);} }
    private static List<Path> imageFiles(Path dir){List<Path>out=new ArrayList<>();if(!Files.isDirectory(dir))return out;try(var stream=Files.list(dir)){stream.filter(Files::isRegularFile).filter(p->{String n=p.getFileName().toString().toLowerCase();return n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp");}).sorted(Comparator.comparing(p->p.getFileName().toString())).forEach(out::add);}catch(IOException ignored){}return out;}
    private static int[] fit(int w,int h,int maxW,int maxH){if(w<=0||h<=0)return new int[]{maxW,maxH};double ratio=Math.min((double)maxW/w,(double)maxH/h);ratio=Math.min(1d,ratio);return new int[]{Math.max(1,(int)Math.round(w*ratio)),Math.max(1,(int)Math.round(h*ratio))};}
    private static String status(String value){if(value==null)return"";return switch(value){case"DRAFT"->"草稿";case"COMPLETED"->"检查完成";case"RECTIFIED"->"已整改完成";case"RECTIFYING","PENDING_RECTIFICATION"->"整改中";default->value;};}
    private static String result(String value){return"PASS".equals(value)?"是":"FAIL".equals(value)?"否":"未填写";}
    private static String blank(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    private static String formTitle(String value){String title=blank(value,"安全检查").trim();if(title.endsWith("记录表"))return title;if(title.endsWith("记录"))return title+"表";return title+"记录表";}
    private static String shortId(String id){return id==null||id.isBlank()?"—":id.substring(0,Math.min(8,id.length()));}

    private static final class WrapRenderer extends JTextArea implements TableCellRenderer {
        WrapRenderer(){setLineWrap(true);setWrapStyleWord(true);setOpaque(true);setBorder(new EmptyBorder(5,6,5,6));}
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){setText(value==null?"":value.toString());setFont(table.getFont());setForeground(selected?table.getSelectionForeground():table.getForeground());setBackground(selected?table.getSelectionBackground():Color.WHITE);int h=Math.max(34,getPreferredSize().height+8);if(table.getRowHeight(row)!=h)table.setRowHeight(row,h);return this;}
    }
    private static final class ResultRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){Component c=super.getTableCellRendererComponent(table,value,selected,focus,row,column);setHorizontalAlignment(SwingConstants.CENTER);if(!selected){String v=String.valueOf(value);c.setForeground("否".equals(v)?new Color(215,52,58):new Color(22,150,73));}return c;}
    }
}
