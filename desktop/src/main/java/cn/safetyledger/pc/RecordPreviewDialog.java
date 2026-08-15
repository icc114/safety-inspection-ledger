package cn.safetyledger.pc;

import com.google.gson.Gson;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only preview of one locally archived inspection record. */
public final class RecordPreviewDialog extends JDialog {
    private static final Gson GSON = new Gson();
    private final Path folder;
    private final ArchiveService.Record record;

    private RecordPreviewDialog(Window owner, Path folder, ArchiveService.Record record) {
        super(owner, "检查记录预览", ModalityType.APPLICATION_MODAL);
        this.folder = folder;
        this.record = record;
        buildUi();
        setSize(980, 760);
        setMinimumSize(new Dimension(820, 620));
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
            JOptionPane.showMessageDialog(owner,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                    "无法预览检查记录", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.setBorder(new EmptyBorder(14, 16, 6, 16));
        JLabel title = new JLabel(blank(record.templateName, "安全检查") + "记录");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        header.add(title, BorderLayout.NORTH);

        JPanel summary = new JPanel(new GridLayout(3, 4, 10, 6));
        addInfo(summary, "检查日期", record.date);
        addInfo(summary, "检查时间", record.time);
        addInfo(summary, "检查类型", record.type);
        addInfo(summary, "状态", status(record.status));
        addInfo(summary, "检查地点", record.location);
        addInfo(summary, "记录编号", shortId(record.id));
        header.add(summary, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("检查事项", itemsPanel());
        tabs.addTab("整改 / 复查", detailPanel());
        tabs.addTab("照片", photoPanel());
        add(tabs, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton word = new JButton("打开 Word");
        word.addActionListener(e -> openFile(folder.resolve("检查记录.docx"), "找不到检查记录.docx"));
        JButton openFolder = new JButton("打开本地文件夹");
        openFolder.addActionListener(e -> openFile(folder, "找不到本地记录文件夹"));
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        actions.add(word); actions.add(openFolder); actions.add(close);
        add(actions, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private JPanel itemsPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        DefaultTableModel model = new DefaultTableModel(new Object[]{"序号", "检查类别", "检查内容及标准", "结果", "现场问题"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        if (record.items != null) {
            for (ArchiveService.Item item : record.items) {
                String content = blank(item.content, "");
                if (item.standard != null && !item.standard.isBlank() && !item.standard.equals(item.content)) {
                    content += "\n标准：" + item.standard;
                }
                model.addRow(new Object[]{item.order, blank(item.category, ""), content, result(item.result), blank(item.problem, "")});
            }
        }
        JTable table = new JTable(model);
        table.setRowHeight(44);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(48);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(430);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(240);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel detailPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 8, 8));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(textSection("整改记录", record.rectification));
        panel.add(textSection("复查说明", record.recheck));
        return panel;
    }

    private JPanel textSection(String title, String value) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        JTextArea text = new JTextArea(blank(value, "无"));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setMargin(new Insets(8, 8, 8, 8));
        panel.add(label, BorderLayout.NORTH);
        panel.add(new JScrollPane(text), BorderLayout.CENTER);
        return panel;
    }

    private JComponent photoPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        addPhotoSection(root, "检查照片", folder.resolve("检查照片"));
        addPhotoSection(root, "整改照片", folder.resolve("整改照片"));
        addPhotoSection(root, "复查照片", folder.resolve("复查照片"));
        JScrollPane scroll = new JScrollPane(root);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private void addPhotoSection(JPanel root, String title, Path dir) {
        List<Path> files = imageFiles(dir);
        JLabel heading = new JLabel(title + "（" + files.size() + " 张）");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        heading.setBorder(new EmptyBorder(6, 0, 5, 0));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(heading);
        JPanel gallery = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        gallery.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (files.isEmpty()) {
            gallery.add(new JLabel("无"));
        } else {
            for (Path file : files) gallery.add(photoCard(file));
        }
        root.add(gallery);
    }

    private JComponent photoCard(Path file) {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(190, 155));
        button.setVerticalTextPosition(SwingConstants.BOTTOM);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setText(file.getFileName().toString());
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image != null) {
                int[] size = fit(image.getWidth(), image.getHeight(), 170, 112);
                button.setIcon(new ImageIcon(image.getScaledInstance(size[0], size[1], Image.SCALE_SMOOTH)));
            }
        } catch (Exception ignored) {}
        button.addActionListener(e -> openImagePreview(file));
        return button;
    }

    private void openImagePreview(Path file) {
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) throw new IOException("无法读取图片");
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int[] size = fit(image.getWidth(), image.getHeight(), Math.min(1100, screen.width - 160), Math.min(760, screen.height - 200));
            JLabel label = new JLabel(new ImageIcon(image.getScaledInstance(size[0], size[1], Image.SCALE_SMOOTH)));
            JOptionPane.showMessageDialog(this, new JScrollPane(label), file.getFileName().toString(), JOptionPane.PLAIN_MESSAGE);
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, "图片预览失败：" + error.getMessage(), "提示", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openFile(Path path, String missing) {
        try {
            if (!Files.exists(path)) throw new IOException(missing);
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "打开失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static List<Path> imageFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    private static void addInfo(JPanel panel, String label, String value) {
        JLabel left = new JLabel(label + "：");
        left.setFont(left.getFont().deriveFont(Font.BOLD));
        panel.add(left);
        panel.add(new JLabel(blank(value, "—")));
    }

    private static int[] fit(int w, int h, int maxW, int maxH) {
        if (w <= 0 || h <= 0) return new int[]{maxW, maxH};
        double ratio = Math.min((double) maxW / w, (double) maxH / h);
        ratio = Math.min(1d, ratio);
        return new int[]{Math.max(1, (int) Math.round(w * ratio)), Math.max(1, (int) Math.round(h * ratio))};
    }

    private static String status(String value) {
        if (value == null) return "";
        return switch (value) {
            case "DRAFT" -> "草稿";
            case "COMPLETED" -> "已完成";
            case "RECTIFIED" -> "已整改完成";
            case "RECTIFYING", "PENDING_RECTIFICATION" -> "整改中";
            default -> value;
        };
    }

    private static String result(String value) {
        return "PASS".equals(value) ? "是" : "FAIL".equals(value) ? "否" : "未填写";
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String shortId(String id) {
        return id == null || id.isBlank() ? "—" : id.substring(0, Math.min(8, id.length()));
    }
}
