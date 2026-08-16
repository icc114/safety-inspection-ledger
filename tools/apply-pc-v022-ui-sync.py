from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / 'desktop/src/main/java/cn/safetyledger/pc/SafetyLedgerDesktop.java'
text = path.read_text(encoding='utf-8')

text = text.replace('super("安全检查台账 PC 0.2.1");', 'super("安全检查台账 PC 0.2.2");')
text = text.replace('new JComboBox<>(new Integer[]{10, 30, 50})', 'new JComboBox<>(new Integer[]{10, 20, 50, 100, 200})')
text = text.replace('    private HolidayCalendarService holidayService;\n', '    private HolidayCalendarService holidayService;\n    private volatile Path latestSyncLog;\n')
text = text.replace('        setSize(1450, 860);\n        setMinimumSize(new Dimension(1120, 720));', '        setSize(1400, 820);\n        setMinimumSize(new Dimension(1040, 650));')


def replace_between(source: str, start_marker: str, end_marker: str, replacement: str) -> str:
    start = source.index(start_marker)
    end = source.index(end_marker, start)
    return source[:start] + replacement + source[end:]


new_header = r'''    private void buildUi() {
        setLayout(new BorderLayout());
        getContentPane().setBackground(BG);
        add(topBar(), BorderLayout.NORTH);

        // PC 0.2.2 removes the old calendar sidebar. The record workspace now uses the full width,
        // matching the mobile client's hierarchy: top action bar -> filters -> record list.
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(BG);
        main.setBorder(new EmptyBorder(14, 14, 8, 14));
        main.add(recordsPanel(), BorderLayout.CENTER);
        add(main, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 4));
        south.setBackground(BG);
        south.setBorder(new EmptyBorder(2, 16, 9, 16));
        JLabel note = new JLabel("电脑端长期保存本地资料；云端仅用于设备传输。双击检查记录可查看详情、照片、Word 和 PDF；同步异常可在设置中查看/导出日志。");
        note.setForeground(MUTED);
        note.setFont(note.getFont().deriveFont(12f));
        status.setForeground(MUTED);
        status.setFont(status.getFont().deriveFont(12f));
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
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(new EmptyBorder(10, 16, 10, 16));
        bar.setBackground(BLUE);

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.setOpaque(false);
        JLabel icon = new JLabel(AppIcon.icon(46));
        icon.setPreferredSize(new Dimension(48, 48));
        brand.add(icon);
        JLabel titleLabel = new JLabel("安全检查台账");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 24f));
        brand.add(titleLabel);
        bar.add(brand, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 9, 3));
        actions.setOpaque(false);
        JButton sync = headerButton("↻  立即同步", true);
        sync.setToolTipText("立即从云端同步其他设备的检查内容");
        sync.addActionListener(e -> sync(true));
        JButton settings = headerButton("⚙  设置", false);
        settings.setToolTipText("同步设置、本地资料库、数据工具和同步日志");
        settings.addActionListener(e -> showSettings());
        actions.add(sync);
        actions.add(settings);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private JButton headerButton(String text, boolean prominent) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 14f));
        button.setMargin(new Insets(8, 16, 8, 16));
        button.setPreferredSize(new Dimension(prominent ? 128 : 102, 42));
        button.setBackground(Color.WHITE);
        button.setForeground(new Color(23, 78, 166));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(176, 198, 228)),
                new EmptyBorder(4, 8, 4, 8)));
        return button;
    }

'''
text = replace_between(text, '    private void buildUi() {', '    private JComponent sidebarPanel() {', new_header)

new_records = r'''    private JComponent recordsPanel() {
        JPanel root = cardPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), new EmptyBorder(12, 12, 10, 12)));
        root.add(filterPanel(), BorderLayout.NORTH);

        table.setAutoCreateRowSorter(true);
        table.setRowHeight(38);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setGridColor(new Color(228, 233, 241));
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.setFont(table.getFont().deriveFont(13f));
        table.getTableHeader().setPreferredSize(new Dimension(10, 40));
        table.getTableHeader().setBackground(new Color(247, 249, 252));
        table.getTableHeader().setForeground(new Color(35, 49, 71));
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD, 13f));
        int[] widths = {50, 100, 72, 220, 145, 210, 120};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(6).setCellRenderer(new StatusRenderer());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow < 0) return;
                if (e.getClickCount() >= 2 || viewCol == 3) openRecordPreview(viewRow);
            }
        });
        table.setToolTipText("点击检查记录或双击任意行查看详情");
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(224, 231, 241)));
        root.add(scroll, BorderLayout.CENTER);
        root.add(pagingPanel(), BorderLayout.SOUTH);
        return root;
    }

    private JComponent filterPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(8, 9));
        wrapper.setOpaque(false);

        JPanel first = new JPanel(new BorderLayout(10, 0));
        first.setOpaque(false);
        JLabel heading = new JLabel("检查记录");
        heading.setForeground(new Color(20, 35, 61));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20f));
        first.add(heading, BorderLayout.WEST);

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        filters.setOpaque(false);
        range.setSelectedItem("本月");
        range.setPreferredSize(new Dimension(92, 34));
        type.setPreferredSize(new Dimension(150, 34));
        statusFilter.setPreferredSize(new Dimension(126, 34));
        keyword.setPreferredSize(new Dimension(190, 34));
        keyword.setToolTipText("可按检查记录、检查类型、地点搜索");
        filters.add(new JLabel("范围")); filters.add(range);
        filters.add(new JLabel("检查类型")); filters.add(type);
        filters.add(new JLabel("状态")); filters.add(statusFilter);
        filters.add(new JLabel("关键词")); filters.add(keyword);
        JButton apply = actionButton("筛选", true);
        apply.addActionListener(e -> { page = 1; applyFilters(); });
        JButton clear = actionButton("清除筛选", false);
        clear.addActionListener(e -> clearFilters());
        filters.add(apply); filters.add(clear);
        first.add(filters, BorderLayout.CENTER);
        wrapper.add(first, BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        actions.setOpaque(false);
        JButton selectPage = actionButton("全选本页", false);
        selectPage.addActionListener(e -> { for (ArchiveService.IndexEntry entry : currentPageEntries) selectedIds.add(entry.id); renderPage(); });
        JButton selectFiltered = actionButton("全选筛选结果", false);
        selectFiltered.addActionListener(e -> { for (ArchiveService.IndexEntry entry : filteredEntries) selectedIds.add(entry.id); renderPage(); });
        JButton clearSelection = actionButton("清空选择", false);
        clearSelection.addActionListener(e -> { selectedIds.clear(); renderPage(); });
        JButton exportSelected = actionButton("导出选中 PDF", false);
        exportSelected.addActionListener(e -> exportPdf(selectedEntries(), "选中记录"));
        JButton exportFiltered = actionButton("导出筛选结果 PDF", false);
        exportFiltered.addActionListener(e -> exportPdf(new ArrayList<>(filteredEntries), "筛选结果"));
        actions.add(selectPage); actions.add(selectFiltered); actions.add(clearSelection); actions.add(exportSelected); actions.add(exportFiltered);
        wrapper.add(actions, BorderLayout.SOUTH);

        range.addActionListener(e -> { page = 1; applyFilters(); });
        type.addActionListener(e -> { page = 1; applyFilters(); });
        statusFilter.addActionListener(e -> { page = 1; applyFilters(); });
        keyword.addActionListener(e -> { page = 1; applyFilters(); });
        return wrapper;
    }

    private JButton actionButton(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(primary ? Font.BOLD : Font.PLAIN, 13f));
        button.setMargin(new Insets(6, 11, 6, 11));
        button.setPreferredSize(new Dimension(Math.max(primary ? 76 : 105, text.length() * 14 + 28), 34));
        button.setBackground(primary ? BLUE : Color.WHITE);
        button.setForeground(primary ? Color.WHITE : new Color(24, 78, 156));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? BLUE : new Color(188, 204, 226)),
                new EmptyBorder(2, 5, 2, 5)));
        return button;
    }

    private JButton primaryButton(String text) { return actionButton(text, true); }

    private JComponent pagingPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(4, 0, 0, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(new JLabel("每页显示"));
        pageSize.setPreferredSize(new Dimension(82, 32));
        left.add(pageSize);
        left.add(new JLabel("条"));
        pageSize.setSelectedItem(10);
        pageSize.addActionListener(e -> { size = (Integer) pageSize.getSelectedItem(); page = 1; renderPage(); });
        panel.add(left, BorderLayout.WEST);

        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        center.setOpaque(false);
        JButton previous = actionButton("上一页", false);
        JButton next = actionButton("下一页", false);
        previous.setPreferredSize(new Dimension(84, 32));
        next.setPreferredSize(new Dimension(84, 32));
        previous.addActionListener(e -> { if (page > 1) { page--; renderPage(); } });
        next.addActionListener(e -> { int pages = pageCount(); if (page < pages) { page++; renderPage(); } });
        pageInfo.setPreferredSize(new Dimension(210, 32));
        center.add(previous); center.add(pageInfo); center.add(next);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

'''
text = replace_between(text, '    private JComponent recordsPanel() {', '    private void clearFilters()', new_records + '    private void clearFilters()')

new_settings_and_sync = r'''    private void showSettings() {
        JTextField endpointField = new JTextField(config.endpoint, 36);
        JTextField spaceField = new JTextField(config.space, 26);
        JTextField archiveField = new JTextField(config.archiveRoot, 32);
        JTextField deviceField = new JTextField(config.deviceName, 26);
        JTextField shiftField = new JTextField(config.shiftDates, 36);
        JPasswordField passwordField = new JPasswordField(config.password, 26);
        deviceField.setEditable(false);
        shiftField.setToolTipText("多个日期用逗号分隔，例如：2026-08-14, 2026-08-18");

        JPanel syncPanel = new JPanel(new GridBagLayout());
        syncPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 5, 6, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        addSettingRow(syncPanel, c, 0, "云同步地址", endpointField, null);
        addSettingRow(syncPanel, c, 1, "同步空间", spaceField, null);
        addSettingRow(syncPanel, c, 2, "同步空间密码", passwordField, null);
        JButton choose = actionButton("选择文件夹", false);
        choose.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(archiveField.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) archiveField.setText(chooser.getSelectedFile().toPath().toString());
        });
        addSettingRow(syncPanel, c, 3, "电脑本地资料库", archiveField, choose);
        addSettingRow(syncPanel, c, 4, "本机设备名称", deviceField, null);
        addSettingRow(syncPanel, c, 5, "倒班日期", shiftField, null);

        JPanel toolsPanel = new JPanel(new GridLayout(3, 2, 10, 10));
        toolsPanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        JButton test = actionButton("测试连接", false);
        JButton archive = actionButton("打开资料库", false);
        JButton importData = actionButton("导入手机数据包", false);
        JButton exportData = actionButton("导出手机兼容数据包", false);
        JButton viewLog = actionButton("查看同步日志", false);
        JButton exportLog = actionButton("导出同步日志", false);
        test.addActionListener(e -> testConnection());
        archive.addActionListener(e -> openArchive());
        importData.addActionListener(e -> importPackage());
        exportData.addActionListener(e -> exportPortable());
        viewLog.addActionListener(e -> viewSyncLog());
        exportLog.addActionListener(e -> exportSyncLog());
        toolsPanel.add(test); toolsPanel.add(archive); toolsPanel.add(importData);
        toolsPanel.add(exportData); toolsPanel.add(viewLog); toolsPanel.add(exportLog);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("同步与存储", syncPanel);
        tabs.addTab("资料库 / 数据工具 / 日志", toolsPanel);
        tabs.setPreferredSize(new Dimension(690, 340));

        int result = JOptionPane.showConfirmDialog(this, tabs, "设置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        try {
            config.endpoint = endpointField.getText().trim();
            config.space = spaceField.getText().trim();
            if (config.space.isBlank()) config.space = "safety-ledger";
            config.password = new String(passwordField.getPassword());
            config.archiveRoot = archiveField.getText().trim();
            config.shiftDates = shiftField.getText().trim();
            if (config.archiveRoot.isBlank()) throw new IllegalArgumentException("请选择电脑本地资料库文件夹");
            Files.createDirectories(config.archivePath());
            config.save();
            holidayService = new HolidayCalendarService(config.privateDir());
            setStatus("设置已保存");
            selectedIds.clear();
            refreshTable();
            refreshHolidayAsync(calendarMonth.getYear());
            migrateWordLayoutAsync();
        } catch (Exception error) {
            showError("保存设置失败", error);
        }
    }

    private static void addSettingRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent field, JComponent extra) {
        c.gridy = row; c.gridx = 0; c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(field, c);
        if (extra != null) { c.gridx = 2; c.weightx = 0; panel.add(extra, c); }
    }

    private void showDataTools(Component owner) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem importItem = new JMenuItem("导入手机数据包");
        importItem.addActionListener(e -> importPackage());
        JMenuItem exportItem = new JMenuItem("导出手机兼容数据包");
        exportItem.addActionListener(e -> exportPortable());
        menu.add(importItem); menu.add(exportItem);
        menu.show(owner, 0, owner.getHeight());
    }

    private SyncLogger newSyncLogger() {
        try {
            SyncLogger logger = new SyncLogger(config.privateDir());
            latestSyncLog = logger.file();
            return logger;
        } catch (Exception error) {
            setStatus("无法创建同步日志：" + friendlyError(error));
            return null;
        }
    }

    private Path currentSyncLog() {
        if (latestSyncLog != null && Files.isRegularFile(latestSyncLog)) return latestSyncLog;
        return SyncLogger.latest(config.privateDir());
    }

    private void viewSyncLog() {
        Path log = currentSyncLog();
        JTextArea area = new JTextArea(SyncLogger.readTail(log, 160000), 30, 105);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        String title = log == null ? "同步日志" : "同步日志 · " + log.getFileName();
        JOptionPane.showMessageDialog(this, scroll, title, JOptionPane.PLAIN_MESSAGE);
    }

    private void exportSyncLog() {
        Path log = currentSyncLog();
        if (log == null || !Files.isRegularFile(log)) {
            JOptionPane.showMessageDialog(this, "暂无可导出的同步日志。请先执行一次测试连接或同步。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("安全检查台账-PC-同步日志-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path destination = chooser.getSelectedFile().toPath();
            Files.copy(log, destination, StandardCopyOption.REPLACE_EXISTING);
            setStatus("同步日志已导出：" + destination);
        } catch (Exception error) {
            showError("导出同步日志失败", error);
        }
    }

    private void showSyncError(String title, Exception error, SyncLogger logger) {
        if (logger != null) logger.error(title, error);
        Path log = logger == null ? currentSyncLog() : logger.file();
        String message = friendlyError(error)
                + "\n\n本地检查资料不会因此丢失。"
                + (log == null ? "" : "\n详细同步日志：" + log);
        Object[] options = {"确定", "查看日志", "导出日志"};
        int choice = JOptionPane.showOptionDialog(this, message, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.ERROR_MESSAGE, null, options, options[0]);
        if (choice == 1) viewSyncLog();
        if (choice == 2) exportSyncLog();
        setStatus(title + "：" + friendlyError(error));
    }

    private void testConnection() {
        if (config.endpoint.isBlank() || config.password.isBlank()) {
            JOptionPane.showMessageDialog(this, "请先在“设置”中填写云同步地址和同步空间密码。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SyncLogger logger = newSyncLogger();
        setStatus("正在测试云端连接…");
        CompletableFuture.runAsync(() -> {
            try {
                if (logger != null) logger.log("测试连接开始 · endpoint=" + config.endpoint + " · space=" + config.space);
                CloudClient client = new CloudClient(config.endpoint, config.space, config.password.toCharArray(), logger);
                client.testReadWrite();
                if (client.isDeviceLoggedOut(config.deviceId)) throw new SecurityException("此电脑已被管理员登出；请先在管理员手机中允许该设备重新加入");
                client.registerPcDevice(config.deviceId, config.deviceName);
                if (logger != null) logger.log("测试连接全部完成");
                SwingUtilities.invokeLater(() -> {
                    setStatus("连接成功 · " + now());
                    JOptionPane.showMessageDialog(this, "连接成功；本电脑已登记到设备管理。", "测试连接", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> showSyncError("测试连接失败", error, logger));
            }
        });
    }

    private void sync(boolean manual) {
        synchronized (syncLock) {
            if (syncing) {
                if (manual) setStatus("同步正在进行，请稍候…");
                return;
            }
            syncing = true;
        }
        SwingUtilities.invokeLater(() -> setStatus("正在同步检查内容…"));
        SyncLogger logger = newSyncLogger();

        CompletableFuture.runAsync(() -> {
            try {
                if (config.endpoint.isBlank() || config.password.isBlank()) {
                    if (manual) SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "请先在“设置”中配置云同步地址和密码。", "提示", JOptionPane.INFORMATION_MESSAGE));
                    return;
                }
                if (logger != null) logger.log((manual ? "手动" : "后台") + "同步开始 · endpoint=" + config.endpoint + " · space=" + config.space);
                Files.createDirectories(config.privateDir());
                Path cache = config.privateDir().resolve("cloud-cache");
                Files.createDirectories(cache);
                Path fingerprintFile = config.privateDir().resolve("cloud-fingerprints.properties");
                Properties fingerprints = load(fingerprintFile);

                CloudClient client = new CloudClient(config.endpoint, config.space, config.password.toCharArray(), logger);
                client.prepare();
                if (client.isDeviceLoggedOut(config.deviceId)) throw new SecurityException("此电脑已被管理员登出；请先在管理员手机中允许该设备重新加入");
                client.registerPcDevice(config.deviceId, config.deviceName);
                List<String> names = client.listSnapshots();
                ArchiveService archiveService = new ArchiveService(config.archivePath());

                int changed = 0, records = 0, failed = 0;
                List<String> failedNames = new ArrayList<>();
                for (String name : names) {
                    try {
                        String fp = client.fingerprint(name);
                        Path local = cache.resolve(safeFile(name));
                        boolean needs = !fp.equals(fingerprints.getProperty(name, "")) || !Files.isRegularFile(local);
                        String stage = needs ? "正在更新设备快照：" : "设备快照无变化：";
                        SwingUtilities.invokeLater(() -> setStatus(stage + name));
                        if (!needs) {
                            if (logger != null) logger.log("跳过未变化快照 · " + name);
                            continue;
                        }
                        client.download(name, local);
                        try (DataPackageCodec.ExtractedPackage pkg = DataPackageCodec.extract(local, config.password.toCharArray())) {
                            List<ArchiveService.Record> written = archiveService.process(pkg, "云同步 · " + name);
                            records += written.size();
                            Path latest = config.privateDir().resolve("latest");
                            DataPackageCodec.copyTree(pkg.root, latest);
                            if (logger != null) logger.log("快照处理完成 · " + name + " · 记录 " + written.size() + " 条");
                        }
                        fingerprints.setProperty(name, fp);
                        changed++;
                    } catch (Exception snapshotError) {
                        failed++;
                        failedNames.add(name);
                        if (logger != null) logger.error("处理设备快照 " + name, snapshotError);
                    }
                }
                store(fingerprintFile, fingerprints);

                if (!names.isEmpty() && failed == names.size()) {
                    throw new IOException("检测到 " + failed + " 个设备快照，但全部处理失败。请查看同步日志定位具体步骤。" + (failedNames.isEmpty() ? "" : " 失败快照：" + String.join("、", failedNames)));
                }

                int finalChanged = changed, finalRecords = records, finalFailed = failed;
                SwingUtilities.invokeLater(() -> {
                    refreshTable();
                    String text = "同步完成 · 更新快照 " + finalChanged + " 个"
                            + (finalRecords > 0 ? " · 处理记录 " + finalRecords + " 条" : "")
                            + (finalFailed > 0 ? " · " + finalFailed + " 个快照失败（已记录日志）" : "")
                            + " · " + now();
                    setStatus(text);
                    if (manual && finalFailed > 0) {
                        JOptionPane.showMessageDialog(this,
                                "同步已完成，但有 " + finalFailed + " 个设备快照处理失败。\n其余成功内容已经保存，本地资料不会丢失。\n可在“设置 → 资料库 / 数据工具 / 日志”中查看或导出同步日志。",
                                "部分同步完成", JOptionPane.WARNING_MESSAGE);
                    }
                });
                if (logger != null) logger.log("同步结束 · changed=" + changed + " · records=" + records + " · failed=" + failed);
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> showSyncError("同步失败", error, logger));
            } finally {
                synchronized (syncLock) { syncing = false; }
            }
        });
    }

'''
text = replace_between(text, '    private void showSettings()', '    private void importPackage()', new_settings_and_sync + '    private void importPackage()')

path.write_text(text, encoding='utf-8')

pom = root / 'desktop/pom.xml'
pom_text = pom.read_text(encoding='utf-8')
pom_text = pom_text.replace('<version>0.2.1</version>', '<version>0.2.2</version>', 1)
pom_text = pom_text.replace('<finalName>safety-ledger-pc-0.2.1-all</finalName>', '<finalName>safety-ledger-pc-0.2.2-all</finalName>')
pom.write_text(pom_text, encoding='utf-8')

readme = root / 'desktop/README.md'
readme_text = readme.read_text(encoding='utf-8')
readme_text = readme_text.replace('# 安全检查台账 PC', '# 安全检查台账 PC 0.2.2', 1)
readme_text += '''\n\n## PC 0.2.2\n\n- 删除首页左侧日历/周检侧栏，检查记录工作区占满主窗口。\n- 顶部按移动端风格收敛为“立即同步 + 设置”；打开资料库、数据工具、测试连接和同步日志统一进入设置。\n- 新增可查看、可导出的详细同步日志，记录每个 WebDAV 请求、HTTP 状态、耗时、快照名称和异常堆栈，但不会记录同步密码或认证令牌。\n- 云端请求增加超时/临时网络错误自动重试；单个设备快照失败不会阻断其他设备快照的成功同步。\n- 顶部应用图标使用超采样和高对比度绘制，在 Windows 标题栏/蓝色顶栏下更清晰。\n'''
readme.write_text(readme_text, encoding='utf-8')

version = root / 'desktop/VERSION_0.2.2.txt'
version.write_text('''安全检查台账 PC 0.2.2\n- 删除首页左侧日历，主列表全宽显示\n- 顶部按钮改为移动端式“立即同步 / 设置”\n- 打开资料库、数据工具、测试连接、同步日志统一移入设置\n- 新增同步日志查看/导出与同步错误日志入口\n- 云端请求增加自动重试、分快照容错和更详细诊断\n- 优化顶部应用图标清晰度\n''', encoding='utf-8')
