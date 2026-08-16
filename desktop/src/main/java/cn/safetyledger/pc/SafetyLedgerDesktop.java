package cn.safetyledger.pc;

import com.google.gson.Gson;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Windows companion client for the safety inspection ledger. */
public final class SafetyLedgerDesktop extends JFrame {
    private static final Gson GSON = new Gson();
    private static final Color BLUE = new Color(29, 103, 218);
    private static final Color BG = new Color(246, 248, 252);
    private static final Color LINE = new Color(218, 227, 240);
    private static final Color MUTED = new Color(91, 103, 121);
    private static final Color SUCCESS = new Color(28, 163, 83);

    private final JLabel status = new JLabel("尚未同步");
    private final JLabel monthTitle = new JLabel();
    private final JLabel todayTitle = new JLabel();
    private final JLabel todaySubTitle = new JLabel();
    private final JPanel calendarGrid = new JPanel(new GridLayout(0, 7, 1, 1));
    private final JPanel weeklyRows = new JPanel();
    private final JLabel monthlyRate = new JLabel("0%", SwingConstants.LEFT);
    private final JLabel monthlyRateNote = new JLabel("已完成 0 / 0 周");
    private final RingPanel monthlyRing = new RingPanel();

    private final JComboBox<String> range = new JComboBox<>(new String[]{"当日", "本月", "本季度", "本年度", "全部"});
    private final JComboBox<String> type = new JComboBox<>(new String[]{"全部检查类型"});
    private final JComboBox<String> statusFilter = new JComboBox<>(new String[]{"全部状态", "待整改", "整改中", "已整改完成", "检查完成"});
    private final JTextField keyword = new JTextField();
    private final JComboBox<Integer> pageSize = new JComboBox<>(new Integer[]{10, 20, 50, 100, 200});
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
    private HolidayCalendarService holidayService;
    private volatile Path latestSyncLog;

    private YearMonth calendarMonth = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();
    private int page = 1;
    private int size = 10;
    private List<ArchiveService.IndexEntry> allEntries = new ArrayList<>();
    private List<ArchiveService.IndexEntry> filteredEntries = new ArrayList<>();
    private List<ArchiveService.IndexEntry> currentPageEntries = new ArrayList<>();
    private final Set<String> selectedIds = new LinkedHashSet<>();

    public SafetyLedgerDesktop() {
        super("安全检查台账 PC 0.2.2");
        config = PcConfig.load();
        holidayService = new HolidayCalendarService(config.privateDir());
        setIconImage(AppIcon.image(64));
        buildUi();
        refreshTable();
        setSize(1400, 820);
        setMinimumSize(new Dimension(1040, 650));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { scheduler.shutdownNow(); }
        });
        scheduler.scheduleWithFixedDelay(() -> {
            if (!config.endpoint.isBlank() && !config.password.isBlank()) sync(false);
        }, 20, 120, TimeUnit.SECONDS);
        SwingUtilities.invokeLater(this::migrateWordLayoutAsync);
        refreshHolidayAsync(calendarMonth.getYear());
    }

    private void buildUi() {
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

    private JComponent sidebarPanel() {
        JPanel side = new JPanel(); side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS)); side.setOpaque(false);
        side.add(todayCard()); side.add(Box.createVerticalStrut(10)); side.add(calendarCard()); side.add(Box.createVerticalStrut(10));
        side.add(weeklyCard()); side.add(Box.createVerticalStrut(10)); side.add(monthlyCard()); side.add(Box.createVerticalGlue()); return side;
    }

    private JComponent todayCard() {
        JPanel card = cardPanel(new BorderLayout(8, 3));
        JLabel label = new JLabel("▣  今日"); label.setForeground(new Color(36, 85, 147)); label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        JPanel body = new JPanel(); body.setOpaque(false); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        todayTitle.setFont(todayTitle.getFont().deriveFont(Font.BOLD, 20f)); todaySubTitle.setForeground(MUTED); todaySubTitle.setFont(todaySubTitle.getFont().deriveFont(12f));
        body.add(todayTitle); body.add(Box.createVerticalStrut(3)); body.add(todaySubTitle);
        card.add(label, BorderLayout.NORTH); card.add(body, BorderLayout.CENTER); updateTodayCard(); return card;
    }

    private void updateTodayCard() {
        LocalDate today = LocalDate.now();
        todayTitle.setText(today.format(DateTimeFormatter.ofPattern("yyyy年M月d日")));
        todaySubTitle.setText(today.format(DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)) + "   ·   节假日联网更新 · 本地缓存");
    }

    private JComponent calendarCard() {
        JPanel card = cardPanel(new BorderLayout(4, 4));
        JPanel monthNav = new JPanel(new BorderLayout(4, 0)); monthNav.setOpaque(false);
        JButton previous = miniButton("‹"); JButton next = miniButton("›");
        previous.addActionListener(e -> changeMonth(-1)); next.addActionListener(e -> changeMonth(1));
        monthTitle.setHorizontalAlignment(SwingConstants.CENTER); monthTitle.setFont(monthTitle.getFont().deriveFont(Font.BOLD, 16f));
        monthNav.add(previous, BorderLayout.WEST); monthNav.add(monthTitle, BorderLayout.CENTER); monthNav.add(next, BorderLayout.EAST);
        card.add(monthNav, BorderLayout.NORTH);
        calendarGrid.setBackground(Color.WHITE); calendarGrid.setPreferredSize(new Dimension(280, 220)); card.add(calendarGrid, BorderLayout.CENTER);
        JLabel legend = new JLabel("★ 有检查记录    班 倒班/调休上班    休 休息日"); legend.setForeground(MUTED); legend.setFont(legend.getFont().deriveFont(11f));
        card.add(legend, BorderLayout.SOUTH); return card;
    }

    private JComponent weeklyCard() {
        JPanel card = cardPanel(new BorderLayout(4, 5));
        JLabel title = new JLabel("●  每周安全检查提醒"); title.setForeground(new Color(36,85,147)); title.setFont(title.getFont().deriveFont(Font.BOLD,14f));
        weeklyRows.setLayout(new BoxLayout(weeklyRows, BoxLayout.Y_AXIS)); weeklyRows.setOpaque(false);
        card.add(title, BorderLayout.NORTH); card.add(weeklyRows, BorderLayout.CENTER); return card;
    }

    private JComponent monthlyCard() {
        JPanel card = cardPanel(new BorderLayout(8, 0));
        JPanel text = new JPanel(); text.setOpaque(false); text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("本月周检完成率"); title.setForeground(new Color(36,85,147)); title.setFont(title.getFont().deriveFont(Font.BOLD,13f));
        monthlyRate.setForeground(BLUE); monthlyRate.setFont(monthlyRate.getFont().deriveFont(Font.BOLD, 24f)); monthlyRateNote.setForeground(MUTED); monthlyRateNote.setFont(monthlyRateNote.getFont().deriveFont(12f));
        text.add(title); text.add(Box.createVerticalStrut(4)); text.add(monthlyRate); text.add(monthlyRateNote);
        monthlyRing.setPreferredSize(new Dimension(72,72));
        card.add(text, BorderLayout.CENTER); card.add(monthlyRing, BorderLayout.EAST); return card;
    }

    private JPanel cardPanel(LayoutManager layout) {
        JPanel card = new JPanel(layout); card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), new EmptyBorder(10, 11, 10, 11))); return card;
    }
    private JButton miniButton(String text) { JButton b=new JButton(text);b.setFocusPainted(false);b.setMargin(new Insets(1,8,1,8));b.setFont(b.getFont().deriveFont(Font.BOLD,20f));b.setBackground(Color.WHITE);return b; }

    private void changeMonth(int delta) {
        LocalDate focus = selectedDate == null ? calendarMonth.atDay(1) : selectedDate;
        calendarMonth = calendarMonth.plusMonths(delta);
        selectedDate = calendarMonth.atDay(Math.min(focus.getDayOfMonth(), calendarMonth.lengthOfMonth()));
        if ("当日".equals(range.getSelectedItem())) range.setSelectedItem("本月");
        page = 1; rebuildCalendar(); applyFilters(); refreshHolidayAsync(calendarMonth.getYear());
    }

    private void rebuildCalendar() {
        calendarGrid.removeAll(); monthTitle.setText(calendarMonth.getYear() + "年" + calendarMonth.getMonthValue() + "月");
        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        for (String weekday : weekdays) { JLabel label=new JLabel(weekday,SwingConstants.CENTER);label.setFont(label.getFont().deriveFont(Font.BOLD,12f));label.setForeground(new Color(52,65,83));calendarGrid.add(label); }
        Set<LocalDate> marked = markedDates(); Set<LocalDate> customShifts = config.shiftDateSet();
        int leading=calendarMonth.atDay(1).getDayOfWeek().getValue()-1; LocalDate first=calendarMonth.atDay(1).minusDays(leading);
        int totalCells=((leading+calendarMonth.lengthOfMonth()+6)/7)*7;
        for(int i=0;i<totalCells;i++){
            LocalDate date=first.plusDays(i); boolean inMonth=YearMonth.from(date).equals(calendarMonth); boolean hasRecord=inMonth&&marked.contains(date); boolean chosen=inMonth&&date.equals(selectedDate);
            HolidayCalendarService.Day h=inMonth?holidayService.day(date):null; boolean shift=inMonth&&((h!=null&&!h.isOffDay)||customShifts.contains(date));
            boolean weekend=date.getDayOfWeek()==DayOfWeek.SATURDAY||date.getDayOfWeek()==DayOfWeek.SUNDAY; boolean rest=inMonth&&!shift&&((h!=null&&h.isOffDay)||weekend);
            String marker=""; if(hasRecord)marker+="<font color='#F5A623'>★</font>"; if(shift||rest){if(hasRecord)marker+="&nbsp;";marker+="<font color='"+(shift?"#1D67DA":"#DE373C")+"'>"+(shift?"班":"休")+"</font>";}
            String numberColor=!inMonth?"#B5BDC9":rest?"#DE373C":chosen?"#FFFFFF":"#1E293B";
            String text="<html><center><font color='"+numberColor+"'>"+date.getDayOfMonth()+"</font>"+(marker.isEmpty()?"":"<br>"+marker)+"</center></html>";
            JButton day=new JButton(text);day.setMargin(new Insets(0,0,0,0));day.setFocusPainted(false);day.setFont(day.getFont().deriveFont(Font.BOLD,12f));
            day.setEnabled(inMonth);day.setBackground(chosen?BLUE:Color.WHITE);day.setOpaque(true);day.setBorder(BorderFactory.createLineBorder(chosen?BLUE:Color.WHITE));
            if(inMonth)day.addActionListener(e->{selectedDate=date;range.setSelectedItem("当日");page=1;rebuildCalendar();applyFilters();});
            calendarGrid.add(day);
        }
        calendarGrid.revalidate(); calendarGrid.repaint(); rebuildWeeklyProgress();
    }

    private void rebuildWeeklyProgress() {
        weeklyRows.removeAll(); Set<LocalDate> marked=markedDates(); int planned=(calendarMonth.lengthOfMonth()+6)/7; int completed=0;
        YearMonth nowMonth=YearMonth.now(); LocalDate today=LocalDate.now();
        int due = calendarMonth.isBefore(nowMonth) ? planned : calendarMonth.equals(nowMonth) ? Math.min(planned,(today.getDayOfMonth()-1)/7+1) : 0;
        for(int i=0;i<planned;i++){
            LocalDate start=calendarMonth.atDay(i*7+1); LocalDate end=calendarMonth.atDay(Math.min(calendarMonth.lengthOfMonth(),i*7+7));
            boolean done=marked.stream().anyMatch(d->!d.isBefore(start)&&!d.isAfter(end)); if(done && i<due)completed++;
            boolean futureBlock=calendarMonth.isAfter(nowMonth)||(calendarMonth.equals(nowMonth)&&start.isAfter(today));
            String state=done?"已完成":futureBlock?"未到期":"待检查"; Color color=done?SUCCESS:futureBlock?new Color(150,158,170):new Color(238,145,28);
            JPanel row=new JPanel(new BorderLayout(5,0));row.setOpaque(false);row.setBorder(new EmptyBorder(4,0,4,0));
            JLabel left=new JLabel("●  第 "+(i+1)+" 周  ("+start.getMonthValue()+"."+start.getDayOfMonth()+" - "+end.getMonthValue()+"."+end.getDayOfMonth()+")");left.setForeground(done?SUCCESS:new Color(55,65,81));
            JLabel right=new JLabel(state);right.setOpaque(true);right.setForeground(color);right.setBackground(done?new Color(232,248,238):futureBlock?new Color(245,246,248):new Color(255,245,228));right.setBorder(new EmptyBorder(3,7,3,7));
            row.add(left,BorderLayout.CENTER);row.add(right,BorderLayout.EAST);weeklyRows.add(row);
        }
        double rate=due==0?0d:completed*100d/due; monthlyRate.setText(String.format(Locale.CHINA,"%.1f%%",rate)); monthlyRateNote.setText("已完成 "+completed+" / "+due+" 周"); monthlyRing.setFraction((float)(rate/100d));
        weeklyRows.revalidate();weeklyRows.repaint();
    }

    private Set<LocalDate> markedDates() { Set<LocalDate>out=new HashSet<>();for(ArchiveService.IndexEntry entry:allEntries)try{LocalDate d=LocalDate.parse(entry.date);if(YearMonth.from(d).equals(calendarMonth))out.add(d);}catch(Exception ignored){}return out; }

    private void refreshHolidayAsync(int year) {
        CompletableFuture.runAsync(() -> { holidayService.refresh(year-1); holidayService.refresh(year); holidayService.refresh(year+1); })
                .whenComplete((v,e)->SwingUtilities.invokeLater(()->{if(calendarMonth.getYear()==year)rebuildCalendar();}));
    }

    private JComponent recordsPanel() {
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

    private void clearFilters(){calendarMonth=YearMonth.now();selectedDate=LocalDate.now();range.setSelectedItem("全部");type.setSelectedItem("全部检查类型");statusFilter.setSelectedItem("全部状态");keyword.setText("");page=1;rebuildCalendar();applyFilters();}
    private void applyFilters(){if(allEntries==null)return;String rangeValue=String.valueOf(range.getSelectedItem()),typeValue=String.valueOf(type.getSelectedItem()),statusValue=String.valueOf(statusFilter.getSelectedItem()),q=keyword.getText().trim().toLowerCase(Locale.ROOT);List<ArchiveService.IndexEntry>out=new ArrayList<>();for(ArchiveService.IndexEntry entry:allEntries){LocalDate date;try{date=LocalDate.parse(entry.date);}catch(Exception invalid){continue;}if(!matchesRange(date,rangeValue))continue;if(!"全部检查类型".equals(typeValue)&&!Objects.equals(typeValue,blank(entry.type)))continue;if(!"全部状态".equals(statusValue)&&!Objects.equals(statusValue,statusText(entry.status)))continue;if(!q.isBlank()){String haystack=(blank(entry.title)+" "+blank(entry.type)+" "+blank(entry.location)).toLowerCase(Locale.ROOT);if(!haystack.contains(q))continue;}out.add(entry);}filteredEntries=out;int pages=pageCount();if(page>pages)page=pages;if(page<1)page=1;renderPage();}
    private boolean matchesRange(LocalDate date,String scope){if("全部".equals(scope))return true;if("当日".equals(scope))return selectedDate!=null&&date.equals(selectedDate);if("本月".equals(scope))return YearMonth.from(date).equals(calendarMonth);if("本年度".equals(scope))return date.getYear()==calendarMonth.getYear();if("本季度".equals(scope)){int q1=(date.getMonthValue()-1)/3,q2=(calendarMonth.getMonthValue()-1)/3;return date.getYear()==calendarMonth.getYear()&&q1==q2;}return true;}
    private int pageCount(){return Math.max(1,(filteredEntries.size()+size-1)/size);}
    private void renderPage(){int pages=pageCount();page=Math.max(1,Math.min(page,pages));int from=Math.min(filteredEntries.size(),(page-1)*size),to=Math.min(filteredEntries.size(),from+size);currentPageEntries=new ArrayList<>(filteredEntries.subList(from,to));renderingTable=true;try{model.setRowCount(0);for(ArchiveService.IndexEntry entry:currentPageEntries)model.addRow(new Object[]{selectedIds.contains(entry.id),entry.date,entry.time,entry.title,blank(entry.type),entry.location,statusText(entry.status)});}finally{renderingTable=false;}pageInfo.setText("第 "+page+" / "+pages+" 页    共 "+filteredEntries.size()+" 条");}
    private List<ArchiveService.IndexEntry> selectedEntries(){List<ArchiveService.IndexEntry>out=new ArrayList<>();for(ArchiveService.IndexEntry entry:allEntries)if(selectedIds.contains(entry.id))out.add(entry);return out;}
    private void refreshTable(){try{Files.createDirectories(config.archivePath());ArchiveService service=new ArchiveService(config.archivePath());allEntries=service.listIndex();refreshTypeChoices();rebuildCalendar();applyFilters();}catch(Exception e){setStatus("读取本地资料库失败："+friendlyError(e));}}
    private void refreshTypeChoices(){String previous=type.getSelectedItem()==null?"全部检查类型":String.valueOf(type.getSelectedItem());LinkedHashSet<String>values=new LinkedHashSet<>();values.add("全部检查类型");for(ArchiveService.IndexEntry entry:allEntries)if(!blank(entry.type).isBlank())values.add(entry.type);type.setModel(new DefaultComboBoxModel<>(values.toArray(new String[0])));type.setSelectedItem(values.contains(previous)?previous:"全部检查类型");}
    private void openRecordPreview(int viewRow){try{int modelRow=table.convertRowIndexToModel(viewRow);if(modelRow<0||modelRow>=currentPageEntries.size())throw new IllegalStateException("记录索引无效");RecordPreviewDialog.open(this,currentPageEntries.get(modelRow).folder);}catch(Exception error){showError("无法预览检查记录",error);}}

    private void showSettings() {
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
                    throw new IllegalStateException("检测到 " + failed + " 个设备快照，但全部处理失败。请查看同步日志定位具体步骤。" + (failedNames.isEmpty() ? "" : " 失败快照：" + String.join("、", failedNames)));
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

    private void importPackage(){JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("选择手机导出的 .safetydata 数据包");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path file=chooser.getSelectedFile().toPath();runTask("正在读取手机数据包…",()->{try(DataPackageCodec.ExtractedPackage pkg=DataPackageCodec.extract(file,config.password.toCharArray())){ArchiveService service=new ArchiveService(config.archivePath());int count=service.process(pkg,"本地导入 · "+file.getFileName()).size();DataPackageCodec.copyTree(pkg.root,config.privateDir().resolve("latest"));return"导入完成 · 已处理 "+count+" 条检查记录";}},this::refreshTable);}
    private void exportPortable(){Path latest=config.privateDir().resolve("latest");if(!Files.isRegularFile(latest.resolve("database.sqlite"))){JOptionPane.showMessageDialog(this,"还没有可导出的同步数据。请先同步云端或导入手机数据包。","提示",JOptionPane.INFORMATION_MESSAGE);return;}JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("安全检查台账-电脑导出-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))+".safetydata"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path out=chooser.getSelectedFile().toPath();runTask("正在生成手机兼容数据包…",()->{DataPackageCodec.createPortable(latest,out);return"数据包已导出，可由 Android 或其他 PC 端直接识别";},null);}
    private void exportPdf(List<ArchiveService.IndexEntry>entries,String label){if(entries==null||entries.isEmpty()){JOptionPane.showMessageDialog(this,"没有可导出的"+label+"。","提示",JOptionPane.INFORMATION_MESSAGE);return;}JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("安全检查台账-"+label+"-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))+".pdf"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path out=chooser.getSelectedFile().toPath();if(!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))out=out.resolveSibling(out.getFileName()+".pdf");Path destination=out;runTask("正在生成 A4 PDF…",()->{List<DesktopPdfExporter.Entry>records=new ArrayList<>();for(ArchiveService.IndexEntry entry:entries)records.add(new DesktopPdfExporter.Entry(loadRecord(entry.folder),entry.folder));DesktopPdfExporter.export(records,destination);return"PDF 导出完成 · "+entries.size()+" 条记录 · "+destination;},null);}
    private ArchiveService.Record loadRecord(Path folder)throws Exception{Path json=folder.resolve("record.json");if(!Files.isRegularFile(json))throw new IllegalStateException("记录缺少 record.json："+folder);ArchiveService.Record record=GSON.fromJson(Files.readString(json,StandardCharsets.UTF_8),ArchiveService.Record.class);if(record==null)throw new IllegalStateException("记录数据为空："+folder);attachSignatures(record,folder.resolve("签名"));return record;}
    private static void attachSignatures(ArchiveService.Record record,Path dir){if(!Files.isDirectory(dir))return;try(var files=Files.list(dir)){for(Path file:files.filter(Files::isRegularFile).toList()){String n=file.getFileName().toString().toUpperCase(Locale.ROOT);if(n.startsWith("INSPECTOR1."))record.signatures.put("INSPECTOR1",file);else if(n.startsWith("INSPECTOR2."))record.signatures.put("INSPECTOR2",file);else if(n.startsWith("INSPECTEE."))record.signatures.put("INSPECTEE",file);}}catch(Exception ignored){}}
    private void openArchive(){try{Files.createDirectories(config.archivePath());Desktop.getDesktop().open(config.archivePath().toFile());}catch(Exception e){showError("打开资料库失败",e);}}
    private void migrateWordLayoutAsync(){runTask("正在检查 Word A4 版式…",()->{int changed=WordLayoutMigrator.migrate(config.archivePath());return changed>0?"已按新版 A4 结构更新 "+changed+" 份系统 Word 检查单":"Word A4 版式已是最新";},this::refreshTable);}
    private <T>void runTask(String message,Callable<T>work,Runnable after){setStatus(message);CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception e){throw new CompletionException(e);}}).whenComplete((result,error)->SwingUtilities.invokeLater(()->{if(error!=null){Throwable cause=error instanceof CompletionException&&error.getCause()!=null?error.getCause():error;showError("操作失败",cause);}else{setStatus(String.valueOf(result)+" · "+now());if(after!=null)after.run();}}));}
    private void setStatus(String text){status.setText(text);}private void showError(String title,Throwable error){String msg=friendlyError(error);setStatus(title+"："+msg);JOptionPane.showMessageDialog(this,msg,title,JOptionPane.ERROR_MESSAGE);}
    private static String friendlyError(Throwable error){String fallback=error==null?"未知错误":error.getClass().getSimpleName();for(Throwable current=error;current!=null;current=current.getCause()){String m=current.getMessage();if(m!=null&&!m.isBlank())fallback=m;String s=m==null?"":m.toLowerCase(Locale.ROOT);if(current instanceof java.net.ConnectException||current instanceof java.net.UnknownHostException||current instanceof java.net.http.HttpTimeoutException||s.contains("failed to connect")||s.contains("timed out")||s.contains("timeout")||s.contains("unable to resolve")||s.contains("network is unreachable")||s.contains("no route to host"))return"网络连接问题：暂时无法连接云同步服务器。请检查电脑网络、VPN/代理和云同步地址后重试；电脑本地资料不会因此丢失。";}return fallback;}
    private static String statusText(String value){if(value==null)return"";return switch(value){case"DRAFT"->"草稿";case"PENDING_RECTIFICATION"->"待整改";case"RECTIFYING"->"整改中";case"RECTIFIED"->"已整改完成";case"COMPLETED"->"检查完成";default->value;};}
    private static String blank(String value){return value==null?"":value;}private static String now(){return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));}private static String safeFile(String name){return name.replaceAll("[^A-Za-z0-9._-]","_");}
    private static Properties load(Path file)throws Exception{Properties p=new Properties();if(Files.isRegularFile(file))try(var in=Files.newInputStream(file)){p.load(in);}return p;}private static void store(Path file,Properties p)throws Exception{Files.createDirectories(file.getParent());try(var out=Files.newOutputStream(file)){p.store(out,"Safety Ledger PC cloud fingerprints");}}

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int col){Component c=super.getTableCellRendererComponent(table,value,selected,focus,row,col);setHorizontalAlignment(SwingConstants.CENTER);if(!selected){String v=String.valueOf(value);if(v.contains("已整改")||v.contains("检查完成")){c.setForeground(SUCCESS);setFont(getFont().deriveFont(Font.BOLD));}else if(v.contains("待整改")){c.setForeground(new Color(236,137,28));}else c.setForeground(new Color(61,73,91));}return c;}
    }
    private static final class RingPanel extends JComponent {
        private float fraction;void setFraction(float f){fraction=Math.max(0f,Math.min(1f,f));repaint();}
        @Override protected void paintComponent(Graphics raw){super.paintComponent(raw);Graphics2D g=(Graphics2D)raw.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int pad=7,w=getWidth()-pad*2,h=getHeight()-pad*2,s=Math.min(w,h);int x=(getWidth()-s)/2,y=(getHeight()-s)/2;g.setStroke(new BasicStroke(6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(new Color(226,232,240));g.drawArc(x,y,s,s,0,360);g.setColor(BLUE);g.drawArc(x,y,s,s,90,-Math.round(360*fraction));}finally{g.dispose();}}
    }

    public static void main(String[]args){SwingUtilities.invokeLater(()->{try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}UIManager.put("Button.arc",8);new SafetyLedgerDesktop().setVisible(true);});}
}
