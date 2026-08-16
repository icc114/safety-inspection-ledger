package cn.safetyledger.pc;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UI/holiday enhancement layer for PC 0.2.1. It deliberately leaves the 0.2.0 sync/export logic intact.
 */
public final class DesktopUiPatch {
    private static final Color BLUE = new Color(36, 103, 183);
    private static final Color PALE = new Color(245, 249, 255);
    private static final Color MUTED = new Color(95, 103, 116);
    private static final Color STAR = new Color(245, 166, 35);
    private static final Color DANGER = new Color(228, 61, 61);
    private static final Color GREEN = new Color(34, 171, 88);

    private DesktopUiPatch() {}

    public static void apply(SafetyLedgerDesktop frame) {
        frame.setTitle("安全检查台账 PC 0.2.1");
        BufferedImage icon = icon();
        if (icon != null) frame.setIconImage(icon);
        frame.setSize(1440, 790);
        frame.setMinimumSize(new Dimension(1120, 660));

        JLabel title = findLabel(frame, "安全检查台账");
        if (title != null && icon != null) {
            title.setIcon(new ImageIcon(icon.getScaledInstance(30, 30, Image.SCALE_SMOOTH)));
            title.setIconTextGap(8);
        }

        JSplitPane split = find(frame, JSplitPane.class);
        if (split != null) {
            split.setResizeWeight(0.36);
            split.setDividerLocation(520);
            split.setDividerSize(7);
        }

        try {
            JPanel calendarGrid = field(frame, "calendarGrid", JPanel.class);
            JLabel selectedDateTitle = field(frame, "selectedDateTitle", JLabel.class);
            JPanel card = (JPanel) calendarGrid.getParent();
            compactCalendarHeader(card, selectedDateTitle);
            ProgressPanel progress = installProgress(card, calendarGrid);

            PcHolidayCache cache = new PcHolidayCache(PcConfig.load());
            ScheduledExecutorService holidayExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pc-holiday-sync");
                t.setDaemon(true); return t;
            });
            holidayExecutor.scheduleWithFixedDelay(() -> {
                try {
                    YearMonth month = field(frame, "calendarMonth", YearMonth.class);
                    cache.syncYearIfStale(month.getYear());
                    cache.syncYearIfStale(LocalDate.now().getYear());
                    cache.syncYearIfStale(LocalDate.now().getYear() + 1);
                } catch (Exception ignored) {}
            }, 1, 12, TimeUnit.HOURS);

            javax.swing.Timer repaintTimer = new javax.swing.Timer(700, e -> {
                decorateCalendar(frame, calendarGrid, cache);
                updateProgress(frame, progress);
                normalizeDateTitle(frame, selectedDateTitle);
            });
            repaintTimer.start();
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    repaintTimer.stop();
                    holidayExecutor.shutdownNow();
                }
            });
            holidayExecutor.execute(() -> {
                int year = LocalDate.now().getYear();
                cache.syncYearIfStale(year);
                cache.syncYearIfStale(year + 1);
            });
        } catch (Exception ignored) {
            // A UI enhancement must not prevent the proven PC client from starting.
        }
    }

    private static void compactCalendarHeader(JPanel card, JLabel selectedDateTitle) {
        Component north = ((BorderLayout) card.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        if (!(north instanceof JPanel head)) return;
        JButton previous = findButton(head, "‹");
        JButton next = findButton(head, "›");
        JButton today = findButton(head, "回到今天");
        if (previous == null || next == null || today == null) return;

        head.removeAll();
        head.setLayout(new BorderLayout(6, 0));
        head.setBorder(new EmptyBorder(0, 0, 4, 0));
        JPanel dateRow = new JPanel(new BorderLayout(5, 0));
        dateRow.setOpaque(false);
        previous.setPreferredSize(new Dimension(38, 34));
        next.setPreferredSize(new Dimension(38, 34));
        selectedDateTitle.setHorizontalAlignment(SwingConstants.CENTER);
        selectedDateTitle.setFont(selectedDateTitle.getFont().deriveFont(Font.BOLD, 16f));
        dateRow.add(previous, BorderLayout.WEST);
        dateRow.add(selectedDateTitle, BorderLayout.CENTER);
        dateRow.add(next, BorderLayout.EAST);
        today.setPreferredSize(new Dimension(92, 34));
        head.add(dateRow, BorderLayout.CENTER);
        head.add(today, BorderLayout.EAST);
        head.revalidate();
    }

    private static ProgressPanel installProgress(JPanel card, JPanel calendarGrid) {
        card.remove(calendarGrid);
        calendarGrid.setLayout(new GridLayout(0, 7, 1, 1));
        calendarGrid.setPreferredSize(new Dimension(365, 246));
        calendarGrid.setMinimumSize(new Dimension(330, 220));

        ProgressPanel progress = new ProgressPanel();
        JPanel center = new JPanel(new BorderLayout(7, 0));
        center.setOpaque(false);
        center.add(calendarGrid, BorderLayout.CENTER);
        center.add(progress.root, BorderLayout.EAST);
        card.add(center, BorderLayout.CENTER);
        card.setPreferredSize(new Dimension(500, 325));

        Component south = ((BorderLayout) card.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
        if (south instanceof JLabel legend) {
            legend.setText("★ 有检查记录   班 调休上班   休 休息日/法定节假日");
            legend.setHorizontalAlignment(SwingConstants.CENTER);
            legend.setFont(legend.getFont().deriveFont(11f));
        }
        card.revalidate();
        return progress;
    }

    private static void decorateCalendar(SafetyLedgerDesktop frame, JPanel grid, PcHolidayCache cache) {
        try {
            YearMonth month = field(frame, "calendarMonth", YearMonth.class);
            LocalDate selected = fieldNullable(frame, "selectedDate", LocalDate.class);
            Set<LocalDate> marked = markedDates(frame, month);
            int leading = month.atDay(1).getDayOfWeek().getValue() - 1;
            LocalDate first = month.atDay(1).minusDays(leading);
            Component[] components = grid.getComponents();
            int dayIndex = 0;
            for (int i = 0; i < components.length; i++) {
                if (!(components[i] instanceof JButton day)) continue;
                LocalDate date = first.plusDays(dayIndex++);
                boolean inMonth = YearMonth.from(date).equals(month);
                if (!inMonth) {
                    day.setText(String.valueOf(date.getDayOfMonth()));
                    day.setForeground(new Color(185, 190, 198));
                    continue;
                }
                PcHolidayCache.Day holiday = cache.day(date);
                boolean work = holiday != null && "WORKDAY".equals(holiday.type);
                boolean officialOff = holiday != null && "HOLIDAY".equals(holiday.type);
                boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
                boolean rest = !work && (officialOff || weekend);
                boolean star = marked.contains(date);
                StringBuilder marker = new StringBuilder();
                if (star) marker.append("<font color='#F5A623'>★</font>");
                if (work || rest) {
                    if (marker.length() > 0) marker.append("&nbsp;");
                    marker.append(work ? "<font color='#2467B7'>班</font>" : "<font color='#E43D3D'>休</font>");
                }
                if (marker.length() > 0) day.setText("<html><center>" + date.getDayOfMonth() + "<br>" + marker + "</center></html>");
                else day.setText(String.valueOf(date.getDayOfMonth()));
                if (selected != null && selected.equals(date)) day.setForeground(BLUE);
                else if (rest) day.setForeground(DANGER);
                else day.setForeground(new Color(25, 28, 34));
                day.setMargin(new Insets(0, 1, 0, 1));
                day.setFont(day.getFont().deriveFont(Font.BOLD, 12f));
            }
            grid.revalidate(); grid.repaint();
        } catch (Exception ignored) {}
    }

    private static void updateProgress(SafetyLedgerDesktop frame, ProgressPanel panel) {
        try {
            YearMonth month = field(frame, "calendarMonth", YearMonth.class);
            Set<LocalDate> dates = markedDates(frame, month);
            int planned = (month.lengthOfMonth() + 6) / 7;
            Set<Integer> doneWeeks = new HashSet<>();
            for (LocalDate date : dates) doneWeeks.add((date.getDayOfMonth() - 1) / 7);
            int done = Math.min(planned, doneWeeks.size());
            int rate = planned == 0 ? 0 : Math.round(done * 100f / planned);
            panel.planValue.setText(planned + "次");
            panel.doneValue.setText(done + "次");
            panel.rate.setText(rate + "%");
        } catch (Exception ignored) {}
    }

    private static void normalizeDateTitle(SafetyLedgerDesktop frame, JLabel title) {
        try {
            LocalDate selected = fieldNullable(frame, "selectedDate", LocalDate.class);
            YearMonth month = field(frame, "calendarMonth", YearMonth.class);
            title.setText(selected == null ? month.getYear() + "年" + month.getMonthValue() + "月"
                    : selected.format(DateTimeFormatter.ofPattern("yyyy年M月d日")));
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static Set<LocalDate> markedDates(SafetyLedgerDesktop frame, YearMonth month) throws Exception {
        List<ArchiveService.IndexEntry> entries = field(frame, "allEntries", List.class);
        Set<LocalDate> out = new HashSet<>();
        for (ArchiveService.IndexEntry entry : entries) {
            try {
                LocalDate date = LocalDate.parse(entry.date);
                if (YearMonth.from(date).equals(month)) out.add(date);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static BufferedImage icon() {
        try { return ImageIO.read(DesktopUiPatch.class.getResourceAsStream("/app-icon.png")); }
        catch (Exception ignored) { return null; }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static <T> T fieldNullable(Object target, String name, Class<T> type) throws Exception {
        return field(target, name, type);
    }

    private static JButton findButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) return button;
            if (component instanceof Container child) {
                JButton found = findButton(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel findLabel(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) return label;
            if (component instanceof Container child) {
                JLabel found = findLabel(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container child) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class ProgressPanel {
        final JPanel root = new JPanel();
        final JLabel planValue = metricValue(BLUE);
        final JLabel doneValue = metricValue(GREEN);
        final JLabel rate = metricValue(BLUE);

        ProgressPanel() {
            root.setPreferredSize(new Dimension(112, 245));
            root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
            root.setBackground(Color.WHITE);
            root.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(222, 228, 236)), new EmptyBorder(9, 8, 9, 8)));
            JLabel title = label("本月检查进度", true, 13f); title.setAlignmentX(Component.CENTER_ALIGNMENT);
            root.add(title); root.add(Box.createVerticalStrut(12));
            addMetric(root, "计划次数", planValue);
            root.add(Box.createVerticalStrut(11));
            addMetric(root, "已完成", doneValue);
            root.add(Box.createVerticalGlue());
            JLabel rateLabel = label("完成率", true, 12f); rateLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            root.add(rateLabel); root.add(Box.createVerticalStrut(5));
            rate.setOpaque(true); rate.setBackground(PALE);
            rate.setBorder(BorderFactory.createLineBorder(BLUE, 2, true));
            rate.setPreferredSize(new Dimension(82, 56)); rate.setMaximumSize(new Dimension(90, 56));
            rate.setAlignmentX(Component.CENTER_ALIGNMENT);
            root.add(rate);
        }

        private static void addMetric(JPanel root, String name, JLabel value) {
            JLabel label = label(name, false, 11f); label.setForeground(MUTED); label.setAlignmentX(Component.CENTER_ALIGNMENT);
            value.setAlignmentX(Component.CENTER_ALIGNMENT);
            root.add(label); root.add(value);
        }
        private static JLabel metricValue(Color color) {
            JLabel label = label("0次", true, 20f); label.setForeground(color); label.setHorizontalAlignment(SwingConstants.CENTER); return label;
        }
        private static JLabel label(String text, boolean bold, float size) {
            JLabel label = new JLabel(text, SwingConstants.CENTER);
            label.setFont(label.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
            return label;
        }
    }
}
