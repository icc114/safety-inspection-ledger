package cn.safetyledger.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.Entities.Page;
import cn.safetyledger.app.data.Entities.Template;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.pdf.PdfExporter;

import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LedgerActivity extends Activity {
    private static final int EXPORT = 701;
    private LedgerRepository repo;
    private LinearLayout records;
    private LinearLayout calendarBox;
    private LinearLayout selectionActions;
    private TextView monthTitle;
    private TextView pageTitle;
    private TextView todayTitle;
    private TextView todaySubtitle;
    private Spinner range;
    private Spinner type;
    private Spinner statusFilter;
    private Spinner pageSize;
    private Button multiToggle;
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
        content.addView(Ui.gap(this, 12));
        content.addView(filterCard());
        content.addView(Ui.gap(this, 12));
        content.addView(recordsCard());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private LinearLayout topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        bar.setBackgroundColor(Ui.BLUE);
        TextView title = Ui.text(this, "安全检查台账", 22, true);
        title.setTextColor(Color.WHITE);
        Button settings = Ui.secondaryButton(this, "基础设置");
        settings.setOnClickListener(view -> Ui.start(this, SettingsActivity.class));
        Button form = Ui.secondaryButton(this, "+ 检查填报");
        form.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        bar.addView(title, Ui.weight(1));
        bar.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 98), Ui.dp(this, 48)));
        bar.addView(Ui.horizontalGap(this, 7));
        bar.addView(form, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 48)));
        return bar;
    }

    private LinearLayout calendarCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout today = Ui.row(this);
        todayTitle = Ui.text(this, "", 20, true);
        todaySubtitle = Ui.text(this, "", 14, false);
        todaySubtitle.setTextColor(Ui.MUTED);
        LinearLayout todayText = Ui.column(this);
        todayText.addView(todayTitle);
        todayText.addView(todaySubtitle);
        Button reset = Ui.secondaryButton(this, "回到今天");
        reset.setMinHeight(Ui.dp(this, 40));
        reset.setOnClickListener(view -> {
            month = YearMonth.now();
            selectedDate = LocalDate.now().toString();
            if (range != null) range.setSelection(0);
            syncCalendar();
            load();
        });
        today.addView(todayText, Ui.weight(1));
        today.addView(reset, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 46)));
        card.addView(today);
        card.addView(Ui.divider(this));

        LinearLayout monthNavigation = Ui.row(this);
        monthTitle = Ui.text(this, "", 20, true);
        Button previous = Ui.secondaryButton(this, "上月");
        Button next = Ui.secondaryButton(this, "下月");
        previous.setOnClickListener(view -> {
            month = month.minusMonths(1);
            selectedDate = null;
            syncCalendar();
            load();
        });
        next.setOnClickListener(view -> {
            month = month.plusMonths(1);
            selectedDate = null;
            syncCalendar();
            load();
        });
        monthNavigation.addView(monthTitle, Ui.weight(1));
        monthNavigation.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 38)));
        monthNavigation.addView(Ui.horizontalGap(this, 6));
        monthNavigation.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 38)));
        card.addView(monthNavigation);
        calendarBox = Ui.column(this);
        card.addView(calendarBox);
        syncCalendar();
        return card;
    }

    private LinearLayout filterCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "显示范围", 15, true));
        range = spinner(new String[]{"当日", "本月", "本季度", "本年度", "全部"});
        range.setSelection(1);
        card.addView(range, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));
        card.addView(Ui.gap(this, 7));
        card.addView(Ui.text(this, "检查类型与整改状态", 15, true));
        LinearLayout filters = Ui.row(this);
        type = spinner(types());
        statusFilter = spinner(new String[]{"全部状态", "待整改", "整改中", "已整改完成", "检查完成"});
        filters.addView(type, Ui.weight(1));
        filters.addView(Ui.horizontalGap(this, 6));
        filters.addView(statusFilter, Ui.weight(1));
        card.addView(filters);
        AdapterView.OnItemSelectedListener listener = new SimpleSelect() {
            @Override void selected() { page = 1; load(); }
        };
        range.setOnItemSelectedListener(listener);
        type.setOnItemSelectedListener(listener);
        statusFilter.setOnItemSelectedListener(listener);
        return card;
    }

    private LinearLayout recordsCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "检查记录", 21, true);
        pageSize = spinner(new String[]{"10 条/页", "20 条/页", "50 条/页", "100 条/页", "200 条/页"});
        pageSize.setOnItemSelectedListener(new SimpleSelect() {
            @Override void selected() {
                size = new int[]{10, 20, 50, 100, 200}[pageSize.getSelectedItemPosition()];
                page = 1;
                load();
            }
        });
        header.addView(title, Ui.weight(1));
        header.addView(pageSize, new LinearLayout.LayoutParams(Ui.dp(this, 118), Ui.dp(this, 46)));
        card.addView(header);
        multiToggle = Ui.secondaryButton(this, "多选导出");
        multiToggle.setOnClickListener(view -> {
            multiMode = !multiMode;
            if (!multiMode) selected.clear();
            multiToggle.setText(multiMode ? "退出多选导出" : "多选导出");
            selectionActions.setVisibility(multiMode ? View.VISIBLE : View.GONE);
            showRecords();
        });
        card.addView(multiToggle);
        selectionActions = Ui.column(this);
        LinearLayout first = Ui.row(this);
        Button all = Ui.secondaryButton(this, "全选当前结果");
        Button none = Ui.secondaryButton(this, "取消全选");
        first.addView(all, Ui.weight(1));
        first.addView(Ui.horizontalGap(this, 6));
        first.addView(none, Ui.weight(1));
        LinearLayout second = Ui.row(this);
        Button export = Ui.button(this, "导出选中");
        Button period = Ui.secondaryButton(this, "导出当前周期");
        second.addView(export, Ui.weight(1));
        second.addView(Ui.horizontalGap(this, 6));
        second.addView(period, Ui.weight(1));
        selectionActions.addView(first);
        selectionActions.addView(Ui.gap(this, 6));
        selectionActions.addView(second);
        selectionActions.setVisibility(View.GONE);
        all.setOnClickListener(view -> selectAll());
        none.setOnClickListener(view -> { selected.clear(); showRecords(); });
        export.setOnClickListener(view -> exportSelected());
        period.setOnClickListener(view -> { selected.clear(); exportSelected(); });
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
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
        spinner.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 10));
        spinner.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        return spinner;
    }

    private String[] types() {
        List<Template> templates = repo.templates(true);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add("全部检查类型");
        for (Template template : templates) values.add(template.category);
        return values.toArray(new String[0]);
    }

    private void syncCalendar() {
        if (calendarBox == null) return;
        LocalDate focus = selectedDate == null ? LocalDate.now() : LocalDate.parse(selectedDate);
        todayTitle.setText(focus.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)));
        Map<String, String[]> focusedHolidays = repo.holidays(focus.toString().substring(0, 7));
        String[] day = focusedHolidays.get(focus.toString());
        todaySubtitle.setText(day == null ? "点击日期查看当天检查记录" : day[0]
                + ("WORKDAY".equals(day[1]) ? " · 调休上班" : " · 法定放假"));
        calendarBox.removeAllViews();
        monthTitle.setText(month.getYear() + "年" + month.getMonthValue() + "月");
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        for (String weekday : weekdays) {
            TextView heading = Ui.text(this, weekday, 13, true);
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
            boolean isMarked = marked.contains(key);
            boolean isSelected = key.equals(selectedDate);
            String[] holiday = holidays.get(key);
            TextView cell = Ui.text(this, String.valueOf(date.getDayOfMonth()), 14, true);
            cell.setPadding(0, 0, 0, 0);
            cell.setGravity(Gravity.CENTER);
            if (!inMonth) cell.setTextColor(Color.rgb(190, 197, 207));
            else if (holiday != null && "HOLIDAY".equals(holiday[1])) cell.setTextColor(Ui.DANGER);
            if (isMarked) cell.setBackground(Ui.shape(this, Ui.BLUE_PALE, Color.rgb(113, 158, 231), 10));
            if (isSelected) {
                cell.setBackground(Ui.shape(this, Color.rgb(91, 105, 120), Color.TRANSPARENT, 10));
                cell.setTextColor(Color.WHITE);
            }
            if (inMonth) cell.setOnClickListener(view -> {
                selectedDate = key;
                range.setSelection(0);
                page = 1;
                syncCalendar();
                load();
            });
            grid.addView(cell, cellParams(34));
        }
        calendarBox.addView(grid);
        TextView legend = Ui.text(this,
                "● 蓝底日期有检查记录    ● 红字法定放假    ● 灰底当前选择", 12, false);
        legend.setTextColor(Ui.MUTED);
        calendarBox.addView(legend);
    }

    private GridLayout.LayoutParams cellParams(int height) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1, 1f));
        params.width = 0;
        params.height = Ui.dp(this, height);
        params.setMargins(Ui.dp(this, 1), 0, Ui.dp(this, 1), 0);
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
        return new String[]{null, "PENDING_RECTIFICATION", "RECTIFYING", "RECTIFIED", "COMPLETED"}
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
                else startActivity(new Intent(this, RecordDetailActivity.class)
                        .putExtra("inspection_id", inspection.id));
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
    }

    private void selectAll() {
        String[] bounds = bounds();
        String selectedType = type.getSelectedItemPosition() == 0 ? null : (String) type.getSelectedItem();
        for (Inspection inspection : repo.list(bounds[0], bounds[1], selectedType,
                selectedStatus(), false, 1, 100000).rows) selected.add(inspection.id);
        showRecords();
    }

    private void exportSelected() {
        exportRecords = new ArrayList<>();
        if (selected.isEmpty()) {
            String[] bounds = bounds();
            String selectedType = type.getSelectedItemPosition() == 0 ? null : (String) type.getSelectedItem();
            for (Inspection inspection : repo.list(bounds[0], bounds[1], selectedType,
                    selectedStatus(), false, 1, 100000).rows) {
                exportRecords.add(repo.inspection(inspection.id));
            }
        } else {
            for (String id : selected) {
                Inspection inspection = repo.inspection(id);
                if (inspection != null) exportRecords.add(inspection);
            }
        }
        if (exportRecords.isEmpty()) {
            Ui.toast(this, "没有可导出的检查记录");
            return;
        }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, "安全检查台账-" + LocalDate.now() + ".pdf"), EXPORT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT && result == RESULT_OK && data != null) {
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                new PdfExporter(this).export(exportRecords, output);
                Ui.toast(this, "已生成 A4 PDF，共 " + exportRecords.size() + " 条记录");
            } catch (Exception error) {
                Ui.toast(this, "PDF 导出失败：" + error.getMessage());
            }
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
        if (records != null) {
            syncCalendar();
            load();
        }
    }
}

