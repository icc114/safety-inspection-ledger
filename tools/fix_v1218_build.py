from pathlib import Path

# Patch generated CloudSyncService source after apply_v1218_sync_trash.py.
p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
t=p.read_text(encoding='utf-8')
t=t.replace('meta.put("inspectionType",inspection.inspectionType)','meta.put("inspectionType",inspection.type)')
p.write_text(t,encoding='utf-8')

# Keep periodic trash polling and immediate trash sync as separate JobScheduler IDs.
p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncScheduler.java')
t=p.read_text(encoding='utf-8')
t=t.replace('public static final int TRASH_JOB_ID = 1142029;',
'''public static final int TRASH_PERIODIC_JOB_ID = 1142029;
    public static final int TRASH_SOON_JOB_ID = 1142030;''')
t=t.replace('new JobInfo.Builder(TRASH_JOB_ID,', 'new JobInfo.Builder(TRASH_PERIODIC_JOB_ID,', 1)
needle='JobInfo job=new JobInfo.Builder(TRASH_PERIODIC_JOB_ID,new ComponentName(context,CloudSyncJobService.class))'
if needle in t:
    t=t.replace(needle,'JobInfo job=new JobInfo.Builder(TRASH_SOON_JOB_ID,new ComponentName(context,CloudSyncJobService.class))',1)
else:
    t=t.replace('JobInfo job=new JobInfo.Builder(TRASH_JOB_ID,new ComponentName(context,CloudSyncJobService.class))',
                'JobInfo job=new JobInfo.Builder(TRASH_SOON_JOB_ID,new ComponentName(context,CloudSyncJobService.class))',1)
t=t.replace('scheduler.cancel(TRASH_JOB_ID);', 'scheduler.cancel(TRASH_PERIODIC_JOB_ID);\n        scheduler.cancel(TRASH_SOON_JOB_ID);')
p.write_text(t,encoding='utf-8')

p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncJobService.java')
t=p.read_text(encoding='utf-8')
t=t.replace('boolean trashJob = params.getJobId() == CloudSyncScheduler.TRASH_JOB_ID;',
'''boolean trashJob = params.getJobId() == CloudSyncScheduler.TRASH_PERIODIC_JOB_ID
                    || params.getJobId() == CloudSyncScheduler.TRASH_SOON_JOB_ID;''')
# Thread name is outside the lambda, so do not reference the lambda-local trashJob variable there.
t=t.replace('''params.getJobId() == CloudSyncScheduler.TRASH_JOB_ID ? "safety-ledger-trash-sync"''',
'''(params.getJobId() == CloudSyncScheduler.TRASH_PERIODIC_JOB_ID
                        || params.getJobId() == CloudSyncScheduler.TRASH_SOON_JOB_ID) ? "safety-ledger-trash-sync"''')
t=t.replace('''trashJob ? "safety-ledger-trash-sync"''',
'''(params.getJobId() == CloudSyncScheduler.TRASH_PERIODIC_JOB_ID
                        || params.getJobId() == CloudSyncScheduler.TRASH_SOON_JOB_ID) ? "safety-ledger-trash-sync"''')
# OOM branch still used the old notify signature in first CI.
t=t.replace('''                repo.putSetting(deviceJob ? "last_device_sync_error" : "last_sync_error", message);
                if (!deviceJob) notifyFailure(message);''',
'''                repo.putSetting(deviceJob ? "last_device_sync_error" : trashJob ? "last_trash_sync_error" : "last_sync_error", message);
                if (!deviceJob && !trashJob) notifyFailure(error, message);''')
p.write_text(t,encoding='utf-8')

# Template UX and hard limits chosen to guarantee a readable one-page A4 form.
p=Path('app/src/main/java/cn/safetyledger/app/TemplateActivity.java')
t=p.read_text(encoding='utf-8')
t=t.replace('import android.os.Bundle;','import android.os.Bundle;\nimport android.text.InputFilter;')
t=t.replace('''public final class TemplateActivity extends Activity {
    private LedgerRepository repo;''','''public final class TemplateActivity extends Activity {
    private static final int MAX_TEMPLATE_ITEMS = 9;
    private static final int MAX_TEMPLATE_NAME = 24;
    private static final int MAX_TEMPLATE_TYPE = 16;
    private static final int MAX_ITEM_CATEGORY = 12;
    private static final int MAX_ITEM_CONTENT = 40;
    private static final int MAX_ITEM_STANDARD = 24;
    private LedgerRepository repo;''')
t=t.replace('''        EditText name = Ui.input(this, "模板名称，例如：车棚检查记录");
        EditText category = Ui.input(this, "检查类型，例如：车棚检查");''','''        EditText name = Ui.input(this, "模板名称，例如：安全检查记录");
        EditText category = Ui.input(this, "检查类型，例如：安全检查");
        name.setSingleLine(true);
        category.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEMPLATE_NAME)});
        category.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEMPLATE_TYPE)});''')
t=t.replace('''        add.setOnClickListener(view -> editItem(template, null));''','''        add.setOnClickListener(view -> {
            if (repo.templateItems(template.id).size() >= MAX_TEMPLATE_ITEMS) {
                Ui.toast(this, "每个模板最多 9 个检查项目，以保证导出 A4 第1页包含完整检查表和签名");
                return;
            }
            editItem(template, null);
        });''')
t=t.replace('''        list.addView(heading);
        list.addView(Ui.gap(this, 7));
        List<TemplateItem> items = repo.templateItems(template.id);''','''        list.addView(heading);
        TextView limitNote = Ui.text(this,
                "版式限制：最多 9 个检查项目；检查内容最多 40 字，检查标准最多 24 字。这样可保证正式 PDF 第1页保留完整检查表与签名，第2页起只放检查/整改照片。",
                12, false);
        limitNote.setTextColor(Ui.MUTED);
        list.addView(limitNote);
        list.addView(Ui.gap(this, 7));
        List<TemplateItem> items = repo.templateItems(template.id);''')
t=t.replace('''        EditText category = Ui.input(this, "检查类别");
        EditText content = Ui.input(this, "检查内容");
        EditText standard = Ui.input(this, "检查标准");''','''        EditText category = Ui.input(this, "检查类别（最多12字）");
        EditText content = Ui.input(this, "检查内容（最多40字）");
        EditText standard = Ui.input(this, "检查标准（最多24字）");
        category.setSingleLine(true);
        content.setSingleLine(true);
        standard.setSingleLine(true);
        category.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_ITEM_CATEGORY)});
        content.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_ITEM_CONTENT)});
        standard.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_ITEM_STANDARD)});''')
t=t.replace('''                    int order = item == null ? repo.templateItems(template.id).size() + 1 : item.order;
                    repo.saveTemplateItem''','''                    if (item == null && repo.templateItems(template.id).size() >= MAX_TEMPLATE_ITEMS) {
                        Ui.toast(this, "当前模板已达到 9 个检查项目上限");
                        return;
                    }
                    int order = item == null ? repo.templateItems(template.id).size() + 1 : item.order;
                    repo.saveTemplateItem''')
p.write_text(t,encoding='utf-8')

# Inspection problem text is also bounded so the A4 problem cell cannot silently overflow.
p=Path('app/src/main/java/cn/safetyledger/app/MainActivity.java')
t=p.read_text(encoding='utf-8')
t=t.replace('import android.provider.MediaStore;','import android.provider.MediaStore;\nimport android.text.InputFilter;')
t=t.replace('''        TextView title = Ui.text(this, model.templateName + "记录表", 23, true);''',
'''        TextView title = Ui.text(this, formTitle(model.templateName), 23, true);''')
t=t.replace('''            EditText problem = Ui.input(this, "请填写发现的问题和整改要求");
            problem.setText(item.problem);''','''            EditText problem = Ui.input(this, "请填写发现的问题和整改要求（最多40字）");
            problem.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
            problem.setText(item.problem);''')
# Add helper before the final class brace.
insert='''
    private String formTitle(String value) {
        String name = value == null || value.isBlank() ? "安全检查" : value.trim();
        if (name.endsWith("记录表")) return name;
        if (name.endsWith("记录")) return name + "表";
        return name + "记录表";
    }
'''
idx=t.rfind('\n}')
if idx<0: raise SystemExit('MainActivity class end not found')
t=t[:idx]+insert+t[idx:]
p.write_text(t,encoding='utf-8')

# Rectification confirmation becomes a real square checkbox; cap rectification text to the PDF summary area.
p=Path('app/src/main/java/cn/safetyledger/app/RecordDetailActivity.java')
t=p.read_text(encoding='utf-8')
t=t.replace('import android.provider.MediaStore;','import android.provider.MediaStore;\nimport android.text.InputFilter;')
t=t.replace('import android.widget.Switch;','import android.widget.CheckBox;')
t=t.replace('private Switch confirmed;','private CheckBox confirmed;')
t=t.replace('''        TextView title = Ui.text(this, model.templateName + "记录表", 22, true);''',
'''        TextView title = Ui.text(this, formTitle(model.templateName), 22, true);''')
t=t.replace('''        rectification = Ui.input(this, "填写具体整改情况");
        rectification.setText(model.rectification);''','''        rectification = Ui.input(this, "填写具体整改情况（最多70字）");
        rectification.setFilters(new InputFilter[]{new InputFilter.LengthFilter(70)});
        rectification.setText(model.rectification);''')
t=t.replace('confirmed = new Switch(this);','confirmed = new CheckBox(this);')
insert='''
    private String formTitle(String value) {
        String name = value == null || value.isBlank() ? "安全检查" : value.trim();
        if (name.endsWith("记录表")) return name;
        if (name.endsWith("记录")) return name + "表";
        return name + "记录表";
    }
'''
idx=t.rfind('\n}')
if idx<0: raise SystemExit('RecordDetailActivity class end not found')
t=t[:idx]+insert+t[idx:]
p.write_text(t,encoding='utf-8')

# Stable A4 row slots: adding the 9th item no longer makes the first eight rows suddenly smaller.
p=Path('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java')
t=p.read_text(encoding='utf-8')
t=t.replace('''    private static final int MARGIN = 24;''','''    private static final int MARGIN = 24;
    private static final int FORM_ITEM_SLOTS = 9;''')
t=t.replace('''        int itemCount = Math.max(1, record.items.size());
        float itemArea = detailY - y;
        float itemHeight = itemArea / itemCount;''','''        int itemCount = Math.max(1, record.items.size());
        int layoutRows = record.items.isEmpty() ? 1 : Math.max(FORM_ITEM_SLOTS, itemCount);
        float itemArea = detailY - y;
        float itemHeight = itemArea / layoutRows;''')
needle='''                y += itemHeight;
            }
        }

        drawInspectionSummary'''
replacement='''                y += itemHeight;
            }
            for (int blank = record.items.size(); blank < FORM_ITEM_SLOTS; blank++) {
                for (int column = 0; column < columns.length - 1; column++) {
                    rect(canvas, columns[column], y, columns[column + 1] - columns[column], itemHeight);
                }
                y += itemHeight;
            }
        }

        drawInspectionSummary'''
if needle not in t:
    raise SystemExit('PdfExporter item-loop anchor not found')
t=t.replace(needle,replacement,1)
t=t.replace('''        return value.endsWith("记录表") ? value : value + "记录表";''','''        if (value.endsWith("记录表")) return value;
        if (value.endsWith("记录")) return value + "表";
        return value + "记录表";''')
t=t.replace('''                ? "已整改完成" : "尚未确认完成";''','''                ? "☑ 已整改完成" : "□ 尚未确认完成";''')
p.write_text(t,encoding='utf-8')

# Keep editable PC Word output aligned with the Android first-page A4 contract.
p=Path('desktop/src/main/java/cn/safetyledger/pc/WordExporter.java')
t=p.read_text(encoding='utf-8')
t=t.replace('public static final int LAYOUT_VERSION = 2;','public static final int LAYOUT_VERSION = 3;')
t=t.replace('''        int itemCount = Math.max(1, record.items == null ? 0 : record.items.size());
        XWPFTable table = table(doc, itemCount + 1, 5, widths);''','''        int realCount = record.items == null ? 0 : record.items.size();
        int itemCount = Math.max(1, realCount);
        int layoutRows = realCount == 0 ? 1 : Math.max(9, realCount);
        XWPFTable table = table(doc, layoutRows + 1, 5, widths);''')
t=t.replace('''        int rowHeight = 10200 / itemCount;''','''        int rowHeight = 10200 / layoutRows;''')
t=t.replace('''        for (int r = 0; r < record.items.size(); r++) {
            ArchiveService.Item item = record.items.get(r);''','''        for (int r = 0; r < record.items.size(); r++) {
            ArchiveService.Item item = record.items.get(r);''')
# Blank rows are already created by the table; make their height explicit after real rows.
needle='''            cell(row.getCell(4), blank(item.problem, ""), itemFont, false, ParagraphAlignment.LEFT);
        }
    }
'''
replacement='''            cell(row.getCell(4), blank(item.problem, ""), itemFont, false, ParagraphAlignment.LEFT);
        }
        for (int r = record.items.size(); r < layoutRows; r++) exactRow(table.getRow(r + 1), rowHeight);
    }
'''
if needle not in t: raise SystemExit('WordExporter item loop anchor not found')
t=t.replace(needle,replacement,1)
t=t.replace('''        return value.endsWith("记录表") ? value : value + "记录表";''','''        if (value.endsWith("记录表")) return value;
        if (value.endsWith("记录")) return value + "表";
        return value + "记录表";''')
t=t.replace('''? "已整改完成" : "尚未确认完成";''','''? "☑ 已整改完成" : "□ 尚未确认完成";''')
p.write_text(t,encoding='utf-8')

# Make CI artifact names match the actual Android version.
for workflow in ['.github/workflows/android-build.yml','.github/workflows/android-release.yml']:
    p=Path(workflow); t=p.read_text(encoding='utf-8').replace('1.2.17','1.2.18'); p.write_text(t,encoding='utf-8')

print('Fixed Android 1.2.18 build; added A4 template limits, stable 9-row layout, checkbox rectification and consistent record titles')
