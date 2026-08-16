package cn.safetyledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.Entities.Page;
import cn.safetyledger.app.data.Entities.Template;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.pdf.PdfExporter;
import cn.safetyledger.app.sync.CloudSyncScheduler;
import cn.safetyledger.app.holiday.HolidaySyncService;

import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LedgerActivity extends Activity {
    private static final int EXPORT_MERGED = 701;
    private static final int EXPORT_DIRECTORY = 702;
    private LedgerRepository repo;
    private LinearLayout records;
    private LinearLayout calendarBox;
    private LinearLayout selectionActions;
    private TextView monthTitle;
    private TextView pageTitle;
    private TextView todayTitle;
    private Spinner range;
    private Spinner type;
    private Spinner statusFilter;
    private Spinner pageSize;
    private Button multiToggle;
    private Button allToggle;
    private boolean multiMode;
    private YearMonth month = YearMonth.now();
    private String selectedDate;
    private int page = 1;
    private int total;
    private int size = 10;
    private final Set<String> selected = new LinkedHashSet<>();
    private List<Inspection> current = new ArrayList<>();
    private List<Inspection> exportRecords;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        repo = new LedgerRepository(this);
        render();
        load();
    }

    private void render() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(topBar());
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 24));
        content.addView(calendarCard());
        content.addView(Ui.gap(this, 8));
        content.addView(filterCard());
        content.addView(Ui.gap(this, 8));
        content.addView(recordsCard());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private LinearLayout topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        bar.setBackgroundColor(Ui.BLUE);
        TextView title = Ui.text(this, "安全检查台账", 20, true);
        title.setTextColor(Color.WHITE);
        ImageButton settings = new ImageButton(this);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setColorFilter(Color.WHITE);
        settings.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        settings.setBackground(Ui.shape(this, Color.TRANSPARENT, 0x55ffffff, 20));
        settings.setContentDescription("基础设置");
        settings.setOnClickListener(view -> Ui.start(this, SettingsActivity.class));
        Button form = Ui.secondaryButton(this, "检查填报");
        form.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        bar.addView(title);
        bar.addView(Ui.horizontalGap(this, 2));
        bar.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        bar.addView(Ui.horizontalGap(this, 0), Ui.weight(1));
        bar.addView(form, new LinearLayout.LayoutParams(Ui.dp(this, 102), Ui.dp(this, 44)));
        return bar;
    }

    private LinearLayout calendarCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 8), Ui.dp(this, 7));

        LinearLayout dateNavigation = Ui.row(this);
        Button previous = Ui.secondaryButton(this, "‹");
        Button next = Ui.secondaryButton(this, "›");
        previous.setTextSize(30);
        next.setTextSize(30);
        previous.setPadding(0, 0, 0, Ui.dp(this, 2));
        next.setPadding(0, 0, 0, Ui.dp(this, 2));
        todayTitle = Ui.text(this, "", 17, true);
        todayTitle.setGravity(Gravity.CENTER);
        Button reset = Ui.secondaryButton(this, "回到今天");
        reset.setMinHeight(Ui.dp(this, 42));
        reset.setTextSize(12f);
        reset.setSingleLine(true);
        reset.setGravity(Gravity.CENTER);
        reset.setIncludeFontPadding(false);
        reset.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);

        previous.setOnClickListener(view -> changeCalendarMonth(-1));
        next.setOnClickListener(view -> changeCalendarMonth(1));
        reset.setOnClickListener(view -> {
            month = YearMonth.now();
            selectedDate = LocalDate.now().toString();
            selected.clear();
            if (range != null) range.setSelection(0);
            requestHolidayRefresh();
            syncCalendar();
            load();
        });

        dateNavigation.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        dateNavigation.addView(todayTitle, Ui.weight(1));
        dateNavigation.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        dateNavigation.addView(Ui.horizontalGap(this, 6));
        dateNavigation.addView(reset, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 42)));
        card.addView(dateNavigation);
        card.addView(Ui.gap(this, 4));

        calendarBox = Ui.row(this);
        card.addView(calendarBox);
        syncCalendar();
        requestHolidayRefresh();
        return card;
    }

    private void changeCalendarMonth(int delta) {
        LocalDate focus = selectedDate == null ? LocalDate.now() : LocalDate.parse(selectedDate);
        month = month.plusMonths(delta);
        int day = Math.min(focus.getDayOfMonth(), month.lengthOfMonth());
        selectedDate = month.atDay(day).toString();
        selected.clear();
        page = 1;
        requestHolidayRefresh();
        syncCalendar();
        load();
    }

    private void requestHolidayRefresh() {
        int year = month.getYear();
        HolidaySyncService.syncYearAsync(this, year, () -> runOnUiThread(() -> {
            if (!isFinishing()) syncCalendar();
        }));
    }

    private LinearLayout filterCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 9), Ui.dp(this, 7), Ui.dp(this, 9), Ui.dp(this, 7));
        LinearLayout filters = Ui.row(this);
        TextView rangeLabel = Ui.text(this, "显示范围", 12, true);
        rangeLabel.setPadding(0, 0, Ui.dp(this, 2), 0);
        rangeLabel.setSingleLine(true);
        rangeLabel.setGravity(Gravity.CENTER);
        rangeLabel.setIncludeFontPadding(false);
        range = spinner(new String[]{"当日", "本月", "本季度", "本年度", "全部"});
        range.setSelection(1);
        type = spinner(types());
        statusFilter = spinner(new String[]{"全部状态", "草稿", "待整改", "整改中", "已整改完成", "检查完成"});
        filters.addView(rangeLabel, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 42)));
        filters.addView(range, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 42)));
        filters.addView(Ui.horizontalGap(this, 4));
        filters.addView(type, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        filters.addView(Ui.horizontalGap(this, 4));
        filters.addView(statusFilter, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        card.addView(filters);
        AdapterView.OnItemSelectedListener listener = new SimpleSelect() {
            @Override void selected() { selected.clear(); page = 1; load(); }
        };
        range.setOnItemSelectedListener(listener);
        type.setOnItemSelectedListener(listener);
        statusFilter.setOnItemSelectedListener(listener);
        return card;
    }

    private LinearLayout recordsCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "检查记录", 19, true);
        pageSize = spinner(new String[]{"10 条/页", "20 条/页", "50 条/页", "100 条/页", "200 条/页"});
        pageSize.setOnItemSelectedListener(new SimpleSelect() {
            @Override void selected() {
                size = new int[]{10, 20, 50, 100, 200}[pageSize.getSelectedItemPosition()];
                page = 1;
                load();
            }
        });
        multiToggle = Ui.compactButton(this, "多选导出", false);
        multiToggle.setOnClickListener(view -> {
            multiMode = !multiMode;
            if (!multiMode) selected.clear();
            multiToggle.setText(multiMode ? "退出多选导出" : "多选导出");
            selectionActions.setVisibility(multiMode ? View.VISIBLE : View.GONE);
            showRecords();
        });
        header.addView(title, Ui.weight(1));
        header.addView(multiToggle, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 42)));
        header.addView(Ui.horizontalGap(this, 6));
        header.addView(pageSize, new LinearLayout.LayoutParams(Ui.dp(this, 126), Ui.dp(this, 42)));
        card.addView(header);
        selectionActions = Ui.column(this);
        LinearLayout actions = Ui.row(this);
        allToggle = Ui.secondaryButton(this, "全选");
        Button export = Ui.button(this, "导出");
        actions.addView(allToggle, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 7));
        actions.addView(export, Ui.weight(1));
        selectionActions.addView(actions);
        selectionActions.setVisibility(View.GONE);
        allToggle.setOnClickListener(view -> toggleSelectAll());
        export.setOnClickListener(view -> showExportOptions());
        card.addView(selectionActions);
        card.addView(Ui.gap(this, 6));
        records = Ui.column(this);
        card.addView(records);
        LinearLayout pager = Ui.row(this);
        Button previous = Ui.secondaryButton(this, "上一页");
        Button next = Ui.secondaryButton(this, "下一页");
        pageTitle = Ui.text(this, "", 14, true);
        pageTitle.setGravity(Gravity.CENTER);
        previous.setOnClickListener(view -> { if (page > 1) { page--; load(); } });
        next.setOnClickListener(view -> { if (page * size < total) { page++; load(); } });
        pager.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 44)));
        pager.addView(pageTitle, Ui.weight(1));
        pager.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 44)));
        card.addView(pager);
        return card;
    }

    private abstract class SimpleSelect implements AdapterView.OnItemSelectedListener {
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selected(); }
        @Override public void onNothingSelected(AdapterView<?> parent) {}
        abstract void selected();
    }

    private Spinner spinner(String[] values) {
    Spinner spinner = new Spinner(this);
    ArrayAdapter<String> adapter = spinnerAdapter(values);
    spinner.setAdapter(adapter);
    spinner.setGravity(Gravity.CENTER);
    spinner.setBackground(Ui.gradientShape(this, Color.WHITE, Color.rgb(245, 248, 253), Color.rgb(190, 205, 228), 11));
    spinner.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
    spinner.setMinimumWidth(0);
    Ui.applyFunctionalDepth(this, spinner);
    return spinner;
}

    private ArrayAdapter<String> spinnerAdapter(String[] values) {
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values) {
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            if (view instanceof TextView text) {
                text.setTextSize(12.5f);
                text.setSingleLine(true);
                text.setGravity(Gravity.CENTER);
                text.setIncludeFontPadding(false);
                text.setTextColor(Ui.TEXT);
                text.setPadding(Ui.dp(LedgerActivity.this, 2), 0, Ui.dp(LedgerActivity.this, 2), 0);
            }
            return view;
        }
        @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View view = super.getDropDownView(position, convertView, parent);
            if (view instanceof TextView text) {
                text.setTextSize(14f);
                text.setTextColor(Ui.TEXT);
                text.setGravity(Gravity.CENTER_VERTICAL);
                text.setPadding(Ui.dp(LedgerActivity.this, 16), Ui.dp(LedgerActivity.this, 10), Ui.dp(LedgerActivity.this, 16), Ui.dp(LedgerActivity.this, 10));
            }
            return view;
        }
    };
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    return adapter;
}

    private String[] types() {
        List<Template> templates = repo.templates(true);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add("全部检查类型");
        for (Template template : templates) values.add(template.category);
        values.addAll(repo.inspectionTypes());
        return values.toArray(new String[0]);
    }

    private void refreshTypeFilter() {
        if (type == null) return;
        String previous = type.getSelectedItem() == null ? "全部检查类型"
                : String.valueOf(type.getSelectedItem());
        String[] values = types();
        type.setAdapter(spinnerAdapter(values));
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (previous.equals(values[i])) { selectedIndex = i; break; }
        }
        type.setSelection(selectedIndex, false);
    }

    private void syncCalendar() {
        if (calendarBox == null) return;
        LocalDate focus;
        try { focus = selectedDate == null ? LocalDate.now() : LocalDate.parse(selectedDate); }
        catch (Exception ignored) { focus = LocalDate.now(); }
        if (!YearMonth.from(focus).equals(month)) {
            focus = month.atDay(Math.min(focus.getDayOfMonth(), month.lengthOfMonth()));
        }
        todayTitle.setText(focus.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)));
        calendarBox.removeAllViews();

        LinearLayout left = Ui.column(this);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        for (String weekday : weekdays) {
            TextView heading = Ui.text(this, weekday, 12, true);
            heading.setIncludeFontPadding(false);
            heading.setPadding(0, 0, 0, 0);
            heading.setGravity(Gravity.CENTER);
            grid.addView(heading, cellParams(26));
        }

        Set<String> marked = repo.markedDates(month.toString());
        Map<String, String[]> holidays = repo.holidays(month.toString());
        int leading = month.atDay(1).getDayOfWeek().getValue() - 1;
        int cells = leading + month.lengthOfMonth();
        int rows = (int) Math.ceil(cells / 7d);
        LocalDate firstVisible = month.atDay(1).minusDays(leading);
        for (int i = 0; i < rows * 7; i++) {
            LocalDate date = firstVisible.plusDays(i);
            String key = date.toString();
            boolean inMonth = YearMonth.from(date).equals(month);
            boolean isMarked = inMonth && marked.contains(key);
            boolean isSelected = inMonth && key.equals(selectedDate);
            String[] holiday = inMonth ? holidays.get(key) : null;
            boolean makeupWorkday = holiday != null && "WORKDAY".equals(holiday[1]);
            boolean statutoryHoliday = holiday != null && "HOLIDAY".equals(holiday[1]);
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            boolean restDay = !makeupWorkday && (statutoryHoliday || weekend);

            SpannableStringBuilder label = new SpannableStringBuilder(String.valueOf(date.getDayOfMonth()));
            if (inMonth && (isMarked || makeupWorkday || restDay)) {
                label.append("\n");
                boolean hasMarker = false;
                if (isMarked) {
                    int start = label.length();
                    label.append("★");
                    label.setSpan(new ForegroundColorSpan(Color.rgb(245, 166, 35)), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    hasMarker = true;
                }
                if (makeupWorkday || restDay) {
                    if (hasMarker) label.append(" ");
                    int start = label.length();
                    label.append(makeupWorkday ? "班" : "休");
                    label.setSpan(new ForegroundColorSpan(makeupWorkday ? Ui.BLUE : Ui.DANGER), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            TextView cell = Ui.text(this, label.toString(), 12, true);
            cell.setText(label);
            cell.setLineSpacing(0, .80f);
            cell.setPadding(0, 0, 0, 0);
            cell.setGravity(Gravity.CENTER);
            if (!inMonth) {
                cell.setTextColor(Color.rgb(188, 195, 205));
            } else if (restDay) {
                cell.setTextColor(Ui.DANGER);
            } else {
                cell.setTextColor(Color.rgb(20, 24, 31));
            }
            if (isSelected) {
                cell.setBackground(Ui.shape(this, Ui.BLUE, Color.TRANSPARENT, 18));
                cell.setTextColor(Color.WHITE);
            }
            if (inMonth) cell.setOnClickListener(view -> {
                selectedDate = key;
                if (range != null) range.setSelection(0);
                page = 1;
                syncCalendar();
                load();
            });
            grid.addView(cell, cellParams(31));
        }
        left.addView(grid);
        TextView legend = Ui.text(this, "★ 有检查记录   班 调休上班   休 休息日/法定节假日", 9, false);
        legend.setPadding(0, 0, 0, 0);
        legend.setTextColor(Ui.MUTED);
        legend.setGravity(Gravity.CENTER);
        left.addView(legend, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 21)));

        calendarBox.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        calendarBox.addView(Ui.horizontalGap(this, 3));
        calendarBox.addView(monthProgressPanel(marked), new LinearLayout.LayoutParams(Ui.dp(this, 76), ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout monthProgressPanel(Set<String> marked) {
        LinearLayout panel = Ui.column(this);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6));
        panel.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 12));
        TextView title = Ui.text(this, "本月检查\n进度", 10, true);
        title.setPadding(0, 0, 0, 0);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        addProgressSpacer(panel);

        int planned = 4;
        Set<Integer> completedWeeks = new HashSet<>();
        for (String date : marked) {
            try {
                LocalDate parsed = LocalDate.parse(date);
                if (YearMonth.from(parsed).equals(month)) completedWeeks.add((parsed.getDayOfMonth() - 1) / 7);
            } catch (Exception ignored) {}
        }
        int completed = Math.min(planned, completedWeeks.size());
        int percent = planned == 0 ? 0 : Math.round(completed * 100f / planned);
        panel.addView(progressMetric("计划次数", planned + "次", Ui.BLUE));
        addProgressSpacer(panel);
        panel.addView(progressMetric("已完成", completed + "次", Color.rgb(38, 177, 91)));
        addProgressSpacer(panel);
        TextView rateLabel = Ui.text(this, "完成率", 9, true);
        rateLabel.setPadding(0, 0, 0, 0);
        rateLabel.setGravity(Gravity.CENTER);
        panel.addView(rateLabel);
        panel.addView(Ui.gap(this, 2));
        DonutProgressView rate = new DonutProgressView(this);
        rate.setProgress(percent);
        LinearLayout.LayoutParams donutParams = new LinearLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 62));
        donutParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(rate, donutParams);
        addProgressSpacer(panel);
        return panel;
    }

    private void addProgressSpacer(LinearLayout panel) {
        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
    }

    private LinearLayout progressMetric(String label, String value, int color) {
        LinearLayout box = Ui.column(this);
        TextView name = Ui.text(this, label, 9, false);
        name.setPadding(0, 0, 0, 0);
        name.setTextColor(Ui.MUTED);
        name.setGravity(Gravity.CENTER);
        TextView number = Ui.text(this, value, 15, true);
        number.setPadding(0, 0, 0, 0);
        number.setTextColor(color);
        number.setGravity(Gravity.CENTER);
        box.addView(name);
        box.addView(number);
        return box;
    }

    private GridLayout.LayoutParams cellParams(int height) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1, 1f));
        params.width = 0;
        params.height = Ui.dp(this, height);
        params.setMargins(0, 0, 0, 0);
        return params;
    }

    private String[] bounds() {
        LocalDate focus = selectedDate == null ? LocalDate.now() : LocalDate.parse(selectedDate);
        int position = range == null ? 1 : range.getSelectedItemPosition();
        if (position == 0) return new String[]{focus.toString(), focus.toString()};
        if (position == 1) {
            LocalDate start = month.atDay(1);
            return new String[]{start.toString(), start.with(TemporalAdjusters.lastDayOfMonth()).toString()};
        }
        if (position == 2) {
            int startMonth = ((focus.getMonthValue() - 1) / 3) * 3 + 1;
            LocalDate start = LocalDate.of(focus.getYear(), startMonth, 1);
            return new String[]{start.toString(),
                    start.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth()).toString()};
        }
        if (position == 3) return new String[]{LocalDate.of(focus.getYear(), 1, 1).toString(),
                LocalDate.of(focus.getYear(), 12, 31).toString()};
        return new String[]{null, null};
    }

    private String selectedStatus() {
        if (statusFilter == null || statusFilter.getSelectedItemPosition() == 0) return null;
        return new String[]{null, "DRAFT", "PENDING_RECTIFICATION", "RECTIFYING", "RECTIFIED", "COMPLETED"}
                [statusFilter.getSelectedItemPosition()];
    }

    private void load() {
        if (records == null || range == null || type == null || statusFilter == null) return;
        String[] bounds = bounds();
        String selectedType = type.getSelectedItemPosition() == 0 ? null : (String) type.getSelectedItem();
        Page<Inspection> result = repo.list(bounds[0], bounds[1], selectedType,
                selectedStatus(), false, page, size);
        current = result.rows;
        total = result.total;
        showRecords();
    }

    private void showRecords() {
        if (records == null) return;
        records.removeAllViews();
        if (current.isEmpty()) {
            TextView empty = Ui.text(this, "当前筛选条件下没有检查记录", 15, false);
            empty.setTextColor(Ui.MUTED);
            empty.setGravity(Gravity.CENTER);
            records.addView(empty);
        }
        for (Inspection inspection : current) {
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4));
            row.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 10));
            CheckBox check = new CheckBox(this);
            check.setVisibility(multiMode ? View.VISIBLE : View.GONE);
            check.setChecked(selected.contains(inspection.id));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(inspection.id); else selected.remove(inspection.id);
                updatePageTitle();
            });
            TextView text = Ui.text(this, inspection.date + " · " + inspection.templateName
                    + "\n" + inspection.location, 15, true);
            TextView state = Ui.text(this, status(inspection.status), 13, true);
            state.setTextColor(inspection.status.startsWith("PENDING") ? Ui.DANGER : Ui.BLUE_DARK);
            text.setOnClickListener(view -> {
                if (multiMode) check.setChecked(!check.isChecked());
                else {
                    Class<?> target = "DRAFT".equals(inspection.status)
                            ? MainActivity.class : RecordDetailActivity.class;
                    startActivity(new Intent(this, target).putExtra("inspection_id", inspection.id));
                }
            });
            row.addView(check);
            row.addView(text, Ui.weight(1));
            row.addView(state);
            records.addView(row);
            records.addView(Ui.gap(this, 7));
        }
        updatePageTitle();
    }

    private void updatePageTitle() {
        if (pageTitle != null) pageTitle.setText(String.format(Locale.CHINA,
                "第 %d 页 · 共 %d 条%s", page, total,
                multiMode ? " · 已选 " + selected.size() + " 条" : ""));
        if (allToggle != null) allToggle.setText(total > 0 && selected.size() == total
                ? "取消全选" : "全选");
    }

    private void toggleSelectAll() {
        if (total > 0 && selected.size() == total) {
            selected.clear();
            showRecords();
            return;
        }
        String[] bounds = bounds();
        String selectedType = type.getSelectedItemPosition() == 0 ? null : (String) type.getSelectedItem();
        selected.clear();
        for (Inspection inspection : repo.list(bounds[0], bounds[1], selectedType,
                selectedStatus(), false, 1, 100000).rows) selected.add(inspection.id);
        showRecords();
    }

    private void showExportOptions() {
        if (selected.isEmpty()) {
            Ui.toast(this, "请先勾选需要导出的检查记录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择导出方式")
                .setItems(new String[]{"逐条导出（每条记录一个 PDF）", "全部导出（合并为一个 PDF）"},
                        (dialog, which) -> {
                            collectSelectedRecords();
                            if (which == 0) startIndividualExport();
                            else chooseMergedSort();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void collectSelectedRecords() {
        exportRecords = new ArrayList<>();
        for (String id : selected) {
            Inspection inspection = repo.inspection(id);
            if (inspection != null) exportRecords.add(inspection);
        }
        sortExportRecords(true);
    }

    private void startIndividualExport() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), EXPORT_DIRECTORY);
    }

    private void chooseMergedSort() {
        new AlertDialog.Builder(this)
                .setTitle("全部导出排序")
                .setSingleChoiceItems(new String[]{"检查日期由前到后", "检查日期由后到前"}, 0,
                        null)
                .setPositiveButton("继续导出", (dialog, which) -> {
                    AlertDialog alert = (AlertDialog) dialog;
                    sortExportRecords(alert.getListView().getCheckedItemPosition() != 1);
                    startMergedExport();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void sortExportRecords(boolean ascending) {
        Comparator<Inspection> comparator = Comparator.comparing(
                inspection -> inspection.date + " " + inspection.time);
        if (!ascending) comparator = comparator.reversed();
        exportRecords.sort(comparator);
    }

    private void startMergedExport() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, "安全检查台账-" + LocalDate.now() + ".pdf"),
                EXPORT_MERGED);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_MERGED && result == RESULT_OK && data != null) {
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                new PdfExporter(this).export(exportRecords, output);
                Ui.toast(this, "已生成 A4 PDF，共 " + exportRecords.size() + " 条记录");
            } catch (Exception error) {
                Ui.toast(this, "PDF 导出失败：" + error.getMessage());
            }
        } else if (request == EXPORT_DIRECTORY && result == RESULT_OK && data != null) {
            exportIndividualPdfs(data.getData(), data.getFlags());
        }
    }

    private void exportIndividualPdfs(Uri treeUri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (Exception ignored) {
            // Some gallery/file providers grant access only for this activity result.
        }
        try {
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));
            int exported = 0;
            for (Inspection inspection : exportRecords) {
                String safeTemplate = inspection.templateName.replaceAll("[\\\\/:*?\"<>|]", "-");
                String name = inspection.date + "-" + safeTemplate + "-"
                        + inspection.id.substring(0, 8) + ".pdf";
                Uri file = DocumentsContract.createDocument(getContentResolver(), directory,
                        "application/pdf", name);
                if (file == null) throw new IllegalStateException("无法创建 " + name);
                try (OutputStream output = getContentResolver().openOutputStream(file)) {
                    new PdfExporter(this).export(List.of(inspection), output);
                }
                exported++;
            }
            Ui.toast(this, "逐条导出完成，共生成 " + exported + " 个 PDF");
        } catch (Exception error) {
            Ui.toast(this, "逐条导出失败：" + error.getMessage());
        }
    }

    private String status(String status) {
        return switch (status) {
            case "PENDING_RECTIFICATION" -> "待整改";
            case "RECTIFYING" -> "整改中";
            case "RECTIFIED" -> "已整改完成";
            case "COMPLETED" -> "检查完成";
            default -> "草稿";
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        CloudSyncScheduler.scheduleTrashSoon(this);
        if (records != null) {
            refreshTypeFilter();
            syncCalendar();
            load();
        }
    }
}
