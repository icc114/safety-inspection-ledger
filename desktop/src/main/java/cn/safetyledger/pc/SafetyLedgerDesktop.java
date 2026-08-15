package cn.safetyledger.pc;

import com.google.gson.Gson;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Windows companion client for the safety inspection ledger. */
public final class SafetyLedgerDesktop extends JFrame {
    private static final Gson GSON = new Gson();
    private static final Color BLUE = new Color(36, 103, 183);
    private static final Color BLUE_PALE = new Color(235, 243, 253);
    private static final Color MUTED = new Color(90, 96, 105);
    private static final Color STAR = new Color(245, 166, 35);

    private final JLabel status = new JLabel("尚未同步");
    private final JLabel monthTitle = new JLabel();
    private final JLabel selectedDateTitle = new JLabel();
    private final JPanel calendarGrid = new JPanel(new GridLayout(0, 7, 3, 3));
    private final JComboBox<String> range = new JComboBox<>(new String[]{"当日", "本月", "本季度", "本年度", "全部"});
    private final JComboBox<String> type = new JComboBox<>(new String[]{"全部检查类型"});
    private final JComboBox<String> statusFilter = new JComboBox<>(new String[]{"全部状态", "待整改", "整改中", "已整改完成", "检查完成"});
    private final JTextField keyword = new JTextField();
    private final JComboBox<Integer> pageSize = new JComboBox<>(new Integer[]{10, 30, 50});
    private final JLabel pageInfo = new JLabel("第 1 / 1 页", SwingConstants.CENTER);

    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"选择", "日期", "时间", "检查记录", "检查类型", "地点", "状态"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Class<?> getColumnClass(int columnIndex) { return columnIndex == 0 ? Boolean.class : String.class; }
    };
    private final JTable table = new JTable(model);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "safety-ledger-pc-auto-sync"); t.setDaemon(true); return t;
    });
    private final Object syncLock = new Object();
    private volatile boolean syncing;
    private volatile boolean renderingTable;
    private PcConfig config;

    private YearMonth calendarMonth = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();
    private int page = 1;
    private int size = 10;
    private List<ArchiveService.IndexEntry> allEntries = new ArrayList<>();
    private List<ArchiveService.IndexEntry> filteredEntries = new ArrayList<>();
    private List<ArchiveService.IndexEntry> currentPageEntries = new ArrayList<>();
    private final Set<String> selectedIds = new LinkedHashSet<>();

    public SafetyLedgerDesktop() {
        super("安全检查台账 PC 0.2.0");
        config = PcConfig.load();
        buildUi();
        refreshTable();
        setSize(1320, 820);
        setMinimumSize(new Dimension(1050, 700));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { scheduler.shutdownNow(); }
        });
        scheduler.scheduleWithFixedDelay(() -> {
            if (!config.endpoint.isBlank() && !config.password.isBlank()) sync(false);
        }, 20, 120, TimeUnit.SECONDS);
        SwingUtilities.invokeLater(this::migrateWordLayoutAsync);
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));
        add(topBar(), BorderLayout.NORTH);

        JPanel main = new JPanel(new BorderLayout(10, 10));
        main.setBorder(new EmptyBorder(10, 10, 8, 10));
        main.setBackground(new Color(245, 247, 250));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, calendarPanel(), recordsPanel());
        split.setResizeWeight(0.23);
        split.setDividerLocation(300);
        split.setBorder(null);
        main.add(split, BorderLayout.CENTER);
        add(main, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 4));
        south.setBorder(new EmptyBorder(4, 12, 10, 12));
        JLabel note = new JLabel("电脑端长期保存本地资料；云端仅用于设备传输。双击检查记录可查看详情、照片和 Word。电脑人工修改的 Word 不会被同步覆盖。");
        note.setForeground(MUTED);
        south.add(note, BorderLayout.NORTH);
        south.add(status, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        model.addTableModelListener(event -> {
            if (renderingTable || event.getType() != TableModelEvent.UPDATE || event.getColumn() != 0) return;
            int row = event.getFirstRow();
            if (row < 0 || row >= currentPageEntries.size()) return;
            boolean checked = Boolean.TRUE.equals(model.getValueAt(row, 0));
            String id = currentPageEntries.get(row).id;
            if (checked) selectedIds.add(id); else selectedIds.remove(id);
        });
    }

    private JComponent topBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(new EmptyBorder(9, 12, 9, 12));
        bar.setBackground(BLUE);
        JLabel titleLabel = new JLabel("安全检查台账");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 22f));
        bar.add(titleLabel, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        actions.setOpaque(false);
        JButton test = headerButton("测试连接");
        test.addActionListener(e -> testConnection());
        JButton sync = headerButton("立即同步");
        sync.addActionListener(e -> sync(true));
        JButton archive = headerButton("打开资料库");
        archive.addActionListener(e -> openArchive());
        JButton tools = headerButton("数据工具 ▾");
        tools.addActionListener(e -> showDataTools(tools));
        JButton settings = headerButton("⚙ 设置");
        settings.addActionListener(e -> showSettings());
        actions.add(test); actions.add(sync); actions.add(archive); actions.add(tools); actions.add(settings);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private JButton headerButton(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setMargin(new Insets(7, 12, 7, 12));
        return button;
    }

    private JComponent calendarPanel() {
        JPanel card = new JPanel(new BorderLayout(7, 7));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 224, 230)), new EmptyBorder(10, 10, 10, 10)));
        card.setBackground(Color.WHITE);

        JPanel head = new JPanel(new BorderLayout(5, 5));
        head.setOpaque(false);
        selectedDateTitle.setFont(selectedDateTitle.getFont().deriveFont(Font.BOLD, 16f));
        head.add(selectedDateTitle, BorderLayout.NORTH);
        JPanel monthNav = new JPanel(new BorderLayout(5, 0));
        monthNav.setOpaque(false);
        JButton previous = new JButton("‹");
        JButton next = new JButton("›");
        previous.addActionListener(e -> changeMonth(-1));
        next.addActionListener(e -> changeMonth(1));
        monthTitle.setHorizontalAlignment(SwingConstants.CENTER);
        monthTitle.setFont(monthTitle.getFont().deriveFont(Font.BOLD, 17f));
        monthNav.add(previous, BorderLayout.WEST); monthNav.add(monthTitle, BorderLayout.CENTER); monthNav.add(next, BorderLayout.EAST);
        head.add(monthNav, BorderLayout.CENTER);
        JButton today = new JButton("回到今天");
        today.addActionListener(e -> {
            calendarMonth = YearMonth.now(); selectedDate = LocalDate.now(); range.setSelectedItem("当日");
            page = 1; rebuildCalendar(); applyFilters();
        });
        head.add(today, BorderLayout.SOUTH);
        card.add(head, BorderLayout.NORTH);

        calendarGrid.setBackground(Color.WHITE);
        card.add(calendarGrid, BorderLayout.CENTER);
        JLabel legend = new JLabel("黄色 ★ 表示当天有检查记录");
        legend.setForeground(MUTED);
        card.add(legend, BorderLayout.SOUTH);
        return card;
    }

    private void changeMonth(int delta) {
        calendarMonth = calendarMonth.plusMonths(delta);
        selectedDate = null;
        if ("当日".equals(range.getSelectedItem())) range.setSelectedItem("本月");
        page = 1;
        rebuildCalendar();
        applyFilters();
    }

    private void rebuildCalendar() {
        calendarGrid.removeAll();
        monthTitle.setText(calendarMonth.getYear() + "年" + calendarMonth.getMonthValue() + "月");
        selectedDateTitle.setText(selectedDate == null ? "选择日期查看记录" : selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日")));
        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        for (String weekday : weekdays) {
            JLabel label = new JLabel(weekday, SwingConstants.CENTER);
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            calendarGrid.add(label);
        }
        Set<LocalDate> marked = markedDates();
        int leading = calendarMonth.atDay(1).getDayOfWeek().getValue() - 1;
        LocalDate first = calendarMonth.atDay(1).minusDays(leading);
        int totalCells = ((leading + calendarMonth.lengthOfMonth() + 6) / 7) * 7;
        for (int i = 0; i < totalCells; i++) {
            LocalDate date = first.plusDays(i);
            boolean inMonth = YearMonth.from(date).equals(calendarMonth);
            boolean hasRecord = marked.contains(date);
            boolean chosen = date.equals(selectedDate);
            String text = hasRecord
                    ? "<html><center>" + date.getDayOfMonth() + "<br><font color='#F5A623'>★</font></center></html>"
                    : String.valueOf(date.getDayOfMonth());
            JButton day = new JButton(text);
            day.setMargin(new Insets(1, 1, 1, 1));
            day.setFocusPainted(false);
            day.setEnabled(inMonth);
            if (!inMonth) day.setForeground(new Color(185, 190, 198));
            if (chosen) {
                day.setOpaque(true); day.setBackground(BLUE_PALE); day.setBorder(BorderFactory.createLineBorder(BLUE, 2));
            }
            if (inMonth) day.addActionListener(e -> {
                selectedDate = date; range.setSelectedItem("当日"); page = 1; rebuildCalendar(); applyFilters();
            });
            calendarGrid.add(day);
        }
        calendarGrid.revalidate(); calendarGrid.repaint();
    }

    private Set<LocalDate> markedDates() {
        Set<LocalDate> out = new HashSet<>();
        for (ArchiveService.IndexEntry entry : allEntries) {
            try { out.add(LocalDate.parse(entry.date)); } catch (Exception ignored) {}
        }
        return out;
    }

    private JComponent recordsPanel() {
        JPanel root = new JPanel(new BorderLayout(7, 7));
        root.setBackground(Color.WHITE);
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 224, 230)), new EmptyBorder(9, 9, 9, 9)));
        root.add(filterPanel(), BorderLayout.NORTH);

        table.setAutoCreateRowSorter(true);
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(42);
        table.getColumnModel().getColumn(1).setPreferredWidth(92);
        table.getColumnModel().getColumn(2).setPreferredWidth(62);
        table.getColumnModel().getColumn(3).setPreferredWidth(180);
        table.getColumnModel().getColumn(4).setPreferredWidth(115);
        table.getColumnModel().getColumn(5).setPreferredWidth(170);
        table.getColumnModel().getColumn(6).setPreferredWidth(105);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow < 0) return;
                if (e.getClickCount() >= 2 || viewCol == 3) openRecordPreview(viewRow);
            }
        });
        table.setToolTipText("点击检查记录或双击任意行查看详情");
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        root.add(pagingPanel(), BorderLayout.SOUTH);
        return root;
    }

    private JComponent filterPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(6, 6));
        wrapper.setOpaque(false);
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filters.setOpaque(false);
        range.setSelectedItem("本月");
        filters.add(new JLabel("范围")); filters.add(range);
        filters.add(new JLabel("检查类型")); filters.add(type);
        filters.add(new JLabel("状态")); filters.add(statusFilter);
        keyword.setPreferredSize(new Dimension(150, 27));
        keyword.setToolTipText("可按检查记录、检查类型、地点搜索");
        filters.add(new JLabel("关键词")); filters.add(keyword);
        JButton apply = new JButton("筛选"); apply.addActionListener(e -> { page = 1; applyFilters(); });
        JButton clear = new JButton("清除筛选"); clear.addActionListener(e -> clearFilters());
        filters.add(apply); filters.add(clear);
        wrapper.add(filters, BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        JButton selectPage = new JButton("全选本页");
        selectPage.addActionListener(e -> { for (ArchiveService.IndexEntry entry : currentPageEntries) selectedIds.add(entry.id); renderPage(); });
        JButton selectFiltered = new JButton("全选筛选结果");
        selectFiltered.addActionListener(e -> { for (ArchiveService.IndexEntry entry : filteredEntries) selectedIds.add(entry.id); renderPage(); });
        JButton clearSelection = new JButton("清空选择");
        clearSelection.addActionListener(e -> { selectedIds.clear(); renderPage(); });
        JButton exportSelected = new JButton("导出选中 PDF");
        exportSelected.addActionListener(e -> exportPdf(selectedEntries(), "选中记录"));
        JButton exportFiltered = new JButton("导出筛选结果 PDF");
        exportFiltered.addActionListener(e -> exportPdf(new ArrayList<>(filteredEntries), "筛选结果"));
        actions.add(selectPage); actions.add(selectFiltered); actions.add(clearSelection); actions.add(exportSelected); actions.add(exportFiltered);
        wrapper.add(actions, BorderLayout.SOUTH);

        range.addActionListener(e -> { page = 1; applyFilters(); });
        type.addActionListener(e -> { page = 1; applyFilters(); });
        statusFilter.addActionListener(e -> { page = 1; applyFilters(); });
        keyword.addActionListener(e -> { page = 1; applyFilters(); });
        return wrapper;
    }

    private JComponent pagingPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); left.setOpaque(false);
        left.add(new JLabel("每页显示")); left.add(pageSize); left.add(new JLabel("条"));
        pageSize.setSelectedItem(10);
        pageSize.addActionListener(e -> { size = (Integer) pageSize.getSelectedItem(); page = 1; renderPage(); });
        panel.add(left, BorderLayout.WEST);

        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0)); center.setOpaque(false);
        JButton previous = new JButton("上一页");
        JButton next = new JButton("下一页");
        previous.addActionListener(e -> { if (page > 1) { page--; renderPage(); } });
        next.addActionListener(e -> { int pages = pageCount(); if (page < pages) { page++; renderPage(); } });
        pageInfo.setPreferredSize(new Dimension(150, 28));
        center.add(previous); center.add(pageInfo); center.add(next);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private void clearFilters() {
        calendarMonth = YearMonth.now();
        selectedDate = LocalDate.now();
        range.setSelectedItem("全部");
        type.setSelectedItem("全部检查类型");
        statusFilter.setSelectedItem("全部状态");
        keyword.setText("");
        page = 1;
        rebuildCalendar();
        applyFilters();
    }

    private void applyFilters() {
        if (allEntries == null) return;
        String rangeValue = String.valueOf(range.getSelectedItem());
        String typeValue = String.valueOf(type.getSelectedItem());
        String statusValue = String.valueOf(statusFilter.getSelectedItem());
        String q = keyword.getText().trim().toLowerCase(Locale.ROOT);
        List<ArchiveService.IndexEntry> out = new ArrayList<>();
        for (ArchiveService.IndexEntry entry : allEntries) {
            LocalDate date;
            try { date = LocalDate.parse(entry.date); } catch (Exception invalid) { continue; }
            if (!matchesRange(date, rangeValue)) continue;
            if (!"全部检查类型".equals(typeValue) && !Objects.equals(typeValue, blank(entry.type))) continue;
            if (!"全部状态".equals(statusValue) && !Objects.equals(statusValue, statusText(entry.status))) continue;
            if (!q.isBlank()) {
                String haystack = (blank(entry.title) + " " + blank(entry.type) + " " + blank(entry.location)).toLowerCase(Locale.ROOT);
                if (!haystack.contains(q)) continue;
            }
            out.add(entry);
        }
        filteredEntries = out;
        int pages = pageCount();
        if (page > pages) page = pages;
        if (page < 1) page = 1;
        renderPage();
    }

    private boolean matchesRange(LocalDate date, String scope) {
        if ("全部".equals(scope)) return true;
        if ("当日".equals(scope)) return selectedDate != null && date.equals(selectedDate);
        if ("本月".equals(scope)) return YearMonth.from(date).equals(calendarMonth);
        if ("本年度".equals(scope)) return date.getYear() == calendarMonth.getYear();
        if ("本季度".equals(scope)) {
            int q1 = (date.getMonthValue() - 1) / 3;
            int q2 = (calendarMonth.getMonthValue() - 1) / 3;
            return date.getYear() == calendarMonth.getYear() && q1 == q2;
        }
        return true;
    }

    private int pageCount() { return Math.max(1, (filteredEntries.size() + size - 1) / size); }

    private void renderPage() {
        int pages = pageCount();
        page = Math.max(1, Math.min(page, pages));
        int from = Math.min(filteredEntries.size(), (page - 1) * size);
        int to = Math.min(filteredEntries.size(), from + size);
        currentPageEntries = new ArrayList<>(filteredEntries.subList(from, to));
        renderingTable = true;
        try {
            model.setRowCount(0);
            for (ArchiveService.IndexEntry entry : currentPageEntries) {
                model.addRow(new Object[]{selectedIds.contains(entry.id), entry.date, entry.time, entry.title,
                        blank(entry.type), entry.location, statusText(entry.status)});
            }
        } finally { renderingTable = false; }
        pageInfo.setText("第 " + page + " / " + pages + " 页 · 共 " + filteredEntries.size() + " 条");
    }

    private List<ArchiveService.IndexEntry> selectedEntries() {
        List<ArchiveService.IndexEntry> out = new ArrayList<>();
        for (ArchiveService.IndexEntry entry : allEntries) if (selectedIds.contains(entry.id)) out.add(entry);
        return out;
    }

    private void refreshTable() {
        try {
            Files.createDirectories(config.archivePath());
            ArchiveService service = new ArchiveService(config.archivePath());
            allEntries = service.listIndex();
            refreshTypeChoices();
            rebuildCalendar();
            applyFilters();
        } catch (Exception e) { setStatus("读取本地资料库失败：" + friendlyError(e)); }
    }

    private void refreshTypeChoices() {
        String previous = type.getSelectedItem() == null ? "全部检查类型" : String.valueOf(type.getSelectedItem());
        LinkedHashSet<String> values = new LinkedHashSet<>(); values.add("全部检查类型");
        for (ArchiveService.IndexEntry entry : allEntries) if (!blank(entry.type).isBlank()) values.add(entry.type);
        DefaultComboBoxModel<String> combo = new DefaultComboBoxModel<>(values.toArray(new String[0]));
        type.setModel(combo);
        type.setSelectedItem(values.contains(previous) ? previous : "全部检查类型");
    }

    private void openRecordPreview(int viewRow) {
        try {
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow < 0 || modelRow >= currentPageEntries.size()) throw new IllegalStateException("记录索引无效");
            RecordPreviewDialog.open(this, currentPageEntries.get(modelRow).folder);
        } catch (Exception error) { showError("无法预览检查记录", error); }
    }

    private void showSettings() {
        JTextField endpointField = new JTextField(config.endpoint, 34);
        JTextField spaceField = new JTextField(config.space, 24);
        JPasswordField passwordField = new JPasswordField(config.password, 24);
        JTextField archiveField = new JTextField(config.archiveRoot, 30);
        JTextField deviceField = new JTextField(config.deviceName, 24); deviceField.setEditable(false);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(5, 5, 5, 5); c.fill = GridBagConstraints.HORIZONTAL;
        addSettingRow(panel, c, 0, "云同步地址", endpointField, null);
        addSettingRow(panel, c, 1, "同步空间", spaceField, null);
        addSettingRow(panel, c, 2, "同步空间密码", passwordField, null);
        JButton choose = new JButton("选择文件夹");
        choose.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(archiveField.getText()); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) archiveField.setText(chooser.getSelectedFile().toPath().toString());
        });
        addSettingRow(panel, c, 3, "电脑本地资料库", archiveField, choose);
        addSettingRow(panel, c, 4, "本机设备名称", deviceField, null);

        int result = JOptionPane.showConfirmDialog(this, panel, "⚙ 设置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        try {
            config.endpoint = endpointField.getText().trim();
            config.space = spaceField.getText().trim(); if (config.space.isBlank()) config.space = "safety-ledger";
            config.password = new String(passwordField.getPassword());
            config.archiveRoot = archiveField.getText().trim();
            if (config.archiveRoot.isBlank()) throw new IllegalArgumentException("请选择电脑本地资料库文件夹");
            Files.createDirectories(config.archivePath());
            config.save();
            setStatus("设置已保存");
            selectedIds.clear();
            refreshTable();
            migrateWordLayoutAsync();
        } catch (Exception error) { showError("保存设置失败", error); }
    }

    private static void addSettingRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent field, JComponent extra) {
        c.gridy = row; c.gridx = 0; c.weightx = 0; panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; panel.add(field, c);
        if (extra != null) { c.gridx = 2; c.weightx = 0; panel.add(extra, c); }
    }

    private void showDataTools(Component owner) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem importItem = new JMenuItem("导入手机数据包"); importItem.addActionListener(e -> importPackage());
        JMenuItem exportItem = new JMenuItem("导出手机兼容数据包"); exportItem.addActionListener(e -> exportPortable());
        menu.add(importItem); menu.add(exportItem);
        menu.show(owner, 0, owner.getHeight());
    }

    private void testConnection() {
        if (config.endpoint.isBlank() || config.password.isBlank()) {
            JOptionPane.showMessageDialog(this, "请先点击右上角“⚙ 设置”填写云同步地址和同步空间密码。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        runTask("正在测试云端读写…", () -> {
            CloudClient client = new CloudClient(config.endpoint, config.space, config.password.toCharArray());
            client.testReadWrite();
            if (client.isDeviceLoggedOut(config.deviceId)) throw new SecurityException("此电脑已被管理员登出；请先在管理员手机中允许该设备重新加入");
            client.registerPcDevice(config.deviceId, config.deviceName);
            return "连接成功；本电脑已登记到设备管理";
        }, null);
    }

    private void sync(boolean manual) {
        synchronized (syncLock) {
            if (syncing) { if (manual) setStatus("已有同步正在进行"); return; }
            syncing = true;
        }
        SwingUtilities.invokeLater(() -> setStatus("正在同步检查内容…"));
        CompletableFuture.runAsync(() -> {
            try {
                if (config.endpoint.isBlank() || config.password.isBlank()) {
                    if (manual) SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "请先在“⚙ 设置”中配置云同步地址和密码。", "提示", JOptionPane.INFORMATION_MESSAGE));
                    return;
                }
                Files.createDirectories(config.privateDir());
                Path cache = config.privateDir().resolve("cloud-cache"); Files.createDirectories(cache);
                Properties fingerprints = load(config.privateDir().resolve("cloud-fingerprints.properties"));
                CloudClient client = new CloudClient(config.endpoint, config.space, config.password.toCharArray());
                client.prepare();
                if (client.isDeviceLoggedOut(config.deviceId)) throw new SecurityException("此电脑已被管理员登出；请先在管理员手机中允许该设备重新加入");
                client.registerPcDevice(config.deviceId, config.deviceName);
                List<String> names = client.listSnapshots();
                ArchiveService archiveService = new ArchiveService(config.archivePath());
                int changed = 0, records = 0;
                for (String name : names) {
                    String fp = client.fingerprint(name);
                    Path local = cache.resolve(safeFile(name));
                    boolean needs = !fp.equals(fingerprints.getProperty(name, "")) || !Files.isRegularFile(local);
                    SwingUtilities.invokeLater(() -> setStatus("正在检查设备快照：" + name));
                    if (needs) {
                        client.download(name, local);
                        try (DataPackageCodec.ExtractedPackage pkg = DataPackageCodec.extract(local, config.password.toCharArray())) {
                            List<ArchiveService.Record> written = archiveService.process(pkg, "云同步 · " + name);
                            records += written.size();
                            Path latest = config.privateDir().resolve("latest");
                            DataPackageCodec.copyTree(pkg.root, latest);
                        }
                        fingerprints.setProperty(name, fp); changed++;
                    }
                }
                store(config.privateDir().resolve("cloud-fingerprints.properties"), fingerprints);
                int finalChanged = changed, finalRecords = records;
                SwingUtilities.invokeLater(() -> {
                    refreshTable();
                    setStatus("同步完成 · 更新快照 " + finalChanged + " 个" + (finalRecords > 0 ? " · 处理记录 " + finalRecords + " 条" : "") + " · " + now());
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> showError("同步失败", e)); }
            finally { synchronized (syncLock) { syncing = false; } }
        });
    }

    private void importPackage() {
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("选择手机导出的 .safetydata 数据包");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        runTask("正在读取手机数据包…", () -> {
            try (DataPackageCodec.ExtractedPackage pkg = DataPackageCodec.extract(file, config.password.toCharArray())) {
                ArchiveService service = new ArchiveService(config.archivePath());
                int count = service.process(pkg, "本地导入 · " + file.getFileName()).size();
                DataPackageCodec.copyTree(pkg.root, config.privateDir().resolve("latest"));
                return "导入完成 · 已处理 " + count + " 条检查记录";
            }
        }, this::refreshTable);
    }

    private void exportPortable() {
        Path latest = config.privateDir().resolve("latest");
        if (!Files.isRegularFile(latest.resolve("database.sqlite"))) {
            JOptionPane.showMessageDialog(this, "还没有可导出的同步数据。请先同步云端或导入手机数据包。", "提示", JOptionPane.INFORMATION_MESSAGE); return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("安全检查台账-电脑导出-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".safetydata"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = chooser.getSelectedFile().toPath();
        runTask("正在生成手机兼容数据包…", () -> { DataPackageCodec.createPortable(latest, out); return "数据包已导出，可由 Android 或其他 PC 端直接识别"; }, null);
    }

    private void exportPdf(List<ArchiveService.IndexEntry> entries, String label) {
        if (entries == null || entries.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可导出的" + label + "。", "提示", JOptionPane.INFORMATION_MESSAGE); return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("安全检查台账-" + label + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".pdf"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = chooser.getSelectedFile().toPath();
        if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) out = out.resolveSibling(out.getFileName() + ".pdf");
        Path destination = out;
        runTask("正在生成 A4 PDF…", () -> {
            List<DesktopPdfExporter.Entry> records = new ArrayList<>();
            for (ArchiveService.IndexEntry entry : entries) records.add(new DesktopPdfExporter.Entry(loadRecord(entry.folder), entry.folder));
            DesktopPdfExporter.export(records, destination);
            return "PDF 导出完成 · " + entries.size() + " 条记录 · " + destination;
        }, null);
    }

    private ArchiveService.Record loadRecord(Path folder) throws Exception {
        Path json = folder.resolve("record.json");
        if (!Files.isRegularFile(json)) throw new IllegalStateException("记录缺少 record.json：" + folder);
        ArchiveService.Record record = GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), ArchiveService.Record.class);
        if (record == null) throw new IllegalStateException("记录数据为空：" + folder);
        attachSignatures(record, folder.resolve("签名"));
        return record;
    }

    private static void attachSignatures(ArchiveService.Record record, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String n = file.getFileName().toString().toUpperCase(Locale.ROOT);
                if (n.startsWith("INSPECTOR1.")) record.signatures.put("INSPECTOR1", file);
                else if (n.startsWith("INSPECTOR2.")) record.signatures.put("INSPECTOR2", file);
                else if (n.startsWith("INSPECTEE.")) record.signatures.put("INSPECTEE", file);
            }
        } catch (Exception ignored) {}
    }

    private void openArchive() {
        try { Files.createDirectories(config.archivePath()); Desktop.getDesktop().open(config.archivePath().toFile()); }
        catch (Exception e) { showError("打开资料库失败", e); }
    }

    private void migrateWordLayoutAsync() {
        runTask("正在检查 Word A4 版式…", () -> {
            int changed = WordLayoutMigrator.migrate(config.archivePath());
            return changed > 0 ? "已按新版 A4 结构更新 " + changed + " 份系统 Word 检查单" : "Word A4 版式已是最新";
        }, this::refreshTable);
    }

    private <T> void runTask(String message, Callable<T> work, Runnable after) {
        setStatus(message);
        CompletableFuture.supplyAsync(() -> {
            try { return work.call(); } catch (Exception e) { throw new CompletionException(e); }
        }).whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                showError("操作失败", cause);
            } else {
                setStatus(String.valueOf(result) + " · " + now());
                if (after != null) after.run();
            }
        }));
    }

    private void setStatus(String text) { status.setText(text); }
    private void showError(String title, Throwable error) {
        String msg = friendlyError(error); setStatus(title + "：" + msg);
        JOptionPane.showMessageDialog(this, msg, title, JOptionPane.ERROR_MESSAGE);
    }

    private static String friendlyError(Throwable error) {
        String fallback = error == null ? "未知错误" : error.getClass().getSimpleName();
        for (Throwable current = error; current != null; current = current.getCause()) {
            String m = current.getMessage(); if (m != null && !m.isBlank()) fallback = m;
            String s = m == null ? "" : m.toLowerCase(Locale.ROOT);
            if (current instanceof java.net.ConnectException || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.http.HttpTimeoutException || s.contains("failed to connect")
                    || s.contains("timed out") || s.contains("timeout") || s.contains("unable to resolve")
                    || s.contains("network is unreachable") || s.contains("no route to host")) {
                return "网络连接问题：暂时无法连接云同步服务器。请检查电脑网络、VPN/代理和云同步地址后重试；电脑本地资料不会因此丢失。";
            }
        }
        return fallback;
    }

    private static String statusText(String value) {
        if (value == null) return "";
        return switch (value) {
            case "DRAFT" -> "草稿";
            case "PENDING_RECTIFICATION" -> "待整改";
            case "RECTIFYING" -> "整改中";
            case "RECTIFIED" -> "已整改完成";
            case "COMPLETED" -> "检查完成";
            default -> value;
        };
    }

    private static String blank(String value) { return value == null ? "" : value; }
    private static String now() { return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
    private static String safeFile(String name) { return name.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static Properties load(Path file) throws Exception { Properties p = new Properties(); if (Files.isRegularFile(file)) try (var in = Files.newInputStream(file)) { p.load(in); } return p; }
    private static void store(Path file, Properties p) throws Exception { Files.createDirectories(file.getParent()); try (var out = Files.newOutputStream(file)) { p.store(out, "Safety Ledger PC cloud fingerprints"); } }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            new SafetyLedgerDesktop().setVisible(true);
        });
    }
}
