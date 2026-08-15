package cn.safetyledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.safetyledger.app.data.Entities.Template;
import cn.safetyledger.app.data.Entities.TemplateItem;
import cn.safetyledger.app.data.LedgerRepository;

import java.util.List;

public final class TemplateActivity extends Activity {
    private static final int MAX_TEMPLATE_ITEMS = 9;
    private static final int MAX_TEMPLATE_NAME = 16;
    private static final int MAX_TEMPLATE_TYPE = 16;
    private static final int MAX_ITEM_CATEGORY = 12;
    private static final int MAX_ITEM_CONTENT = 100;
    private static final int MAX_ITEM_STANDARD = 60;
    private LedgerRepository repo;
    private LinearLayout list;
    private String selectedId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        repo = new LedgerRepository(this);
        render();
    }

    private void render() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(Ui.appBar(this, "检查模板管理"));
        ScrollView scroll = new ScrollView(this);
        list = Ui.column(this);
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        showList();
    }

    private void showList() {
        selectedId = null;
        list.removeAllViews();
        LinearLayout heading = Ui.row(this);
        TextView title = Ui.text(this, "基础设施与检查模板", 19, true);
        Button add = Ui.compactButton(this, "+ 新建模板", true);
        add.setOnClickListener(view -> editTemplate(null));
        heading.addView(title, Ui.weight(1));
        heading.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 40)));
        list.addView(heading);
        list.addView(Ui.gap(this, 7));
        for (Template template : repo.templates(true)) list.addView(templateCard(template));
    }

    private LinearLayout templateCard(Template template) {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout titleRow = Ui.row(this);
        TextView title = Ui.text(this, template.name + "\n" + template.category + " · "
                + template.items.size() + " 个检查项目", 16, true);
        TextView state = Ui.text(this, template.active ? "启用" : "停用", 13, true);
        state.setTextColor(template.active ? Ui.BLUE_DARK : Ui.MUTED);
        state.setBackground(Ui.shape(this, template.active ? Ui.BLUE_PALE : Color.rgb(241, 245, 249),
                Color.TRANSPARENT, 14));
        titleRow.addView(title, Ui.weight(1));
        titleRow.addView(state);
        card.addView(titleRow);
        LinearLayout actions = Ui.row(this);
        Button edit = Ui.compactButton(this, "编辑名称", false);
        Button items = Ui.compactButton(this, "管理检查项", true);
        Button toggle = Ui.compactButton(this, template.active ? "停用" : "启用", false);
        Button delete = Ui.dangerButton(this, "删除");
        edit.setOnClickListener(view -> editTemplate(template));
        items.setOnClickListener(view -> showItems(template));
        toggle.setOnClickListener(view -> {
            repo.saveTemplate(template.id, template.name, template.category, !template.active);
            showList();
        });
        delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("删除模板")
                .setMessage("模板将不再用于新检查，历史记录快照保持原样，可继续查看和导出。")
                .setPositiveButton("删除", (dialog, which) -> {
                    repo.deleteTemplate(template.id);
                    showList();
                })
                .setNegativeButton("取消", null)
                .show());
        actions.addView(edit, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 4));
        actions.addView(items, Ui.weight(1.2f));
        actions.addView(Ui.horizontalGap(this, 4));
        actions.addView(toggle, Ui.weight(.8f));
        actions.addView(Ui.horizontalGap(this, 4));
        actions.addView(delete, Ui.weight(.8f));
        card.addView(actions);
        LinearLayout holder = Ui.column(this);
        holder.addView(card);
        holder.addView(Ui.gap(this, 8));
        return holder;
    }

    private void editTemplate(Template template) {
        LinearLayout form = dialogForm();
        EditText name = Ui.input(this, "模板名称，例如：安全检查记录");
        EditText category = Ui.input(this, "检查类型，例如：安全检查");
        name.setSingleLine(true);
        category.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEMPLATE_NAME)});
        category.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEMPLATE_TYPE)});
        if (template != null) {
            name.setText(template.name);
            category.setText(template.category);
        }
        form.addView(name);
        form.addView(Ui.gap(this, 7));
        form.addView(category);
        new AlertDialog.Builder(this)
                .setTitle(template == null ? "新建模板" : "编辑模板")
                .setView(form)
                .setPositiveButton("保存", (dialog, which) -> {
                    String title = name.getText().toString().trim();
                    String type = category.getText().toString().trim();
                    if (title.isBlank() || type.isBlank()) {
                        Ui.toast(this, "名称和检查类型不能为空");
                        return;
                    }
                    repo.saveTemplate(template == null ? null : template.id, title, type,
                            template == null || template.active);
                    if (template == null) repo.putSetting("current_draft", "");
                    showList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showItems(Template template) {
        selectedId = template.id;
        list.removeAllViews();
        LinearLayout heading = Ui.row(this);
        Button back = Ui.compactButton(this, "‹ 模板列表", false);
        Button add = Ui.compactButton(this, "+ 新增检查项", true);
        back.setOnClickListener(view -> showList());
        add.setOnClickListener(view -> {
            if (repo.templateItems(template.id).size() >= MAX_TEMPLATE_ITEMS) {
                Ui.toast(this, "每个模板最多 9 个检查项目，以保证导出 A4 第1页包含完整检查表和签名");
                return;
            }
            editItem(template, null);
        });
        heading.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 40)));
        heading.addView(Ui.text(this, template.name, 18, true), Ui.weight(1));
        heading.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 118), Ui.dp(this, 40)));
        list.addView(heading);
        TextView limitNote = Ui.text(this,
                "版式限制：最多 9 个检查项目；检查内容最多 100 字，检查标准最多 60 字。正式 PDF 会根据单元格文字量自动调整字号，优先保证第1页检查表、整改意见、整改记录和签名完整，第2页起只放检查/整改照片。",
                12, false);
        limitNote.setTextColor(Ui.MUTED);
        list.addView(limitNote);
        list.addView(Ui.gap(this, 7));
        List<TemplateItem> items = repo.templateItems(template.id);
        if (items.isEmpty()) {
            TextView empty = Ui.text(this, "当前模板还没有检查项目，请点击右上角新增。", 15, false);
            empty.setTextColor(Ui.MUTED);
            list.addView(empty);
            return;
        }
        for (TemplateItem item : items) list.addView(itemCard(template, item));
    }

    private LinearLayout itemCard(Template template, TemplateItem item) {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout contentRow = Ui.row(this);
        TextView number = Ui.text(this, String.valueOf(item.order), 16, true);
        number.setTextColor(Color.WHITE);
        number.setGravity(android.view.Gravity.CENTER);
        number.setBackground(Ui.shape(this, Ui.BLUE, Color.TRANSPARENT, 18));
        TextView content = Ui.text(this, item.category + "\n" + item.content
                + (item.standard.isBlank() ? "" : "\n标准：" + item.standard), 14, true);
        contentRow.addView(number, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        contentRow.addView(content, Ui.weight(1));
        card.addView(contentRow);
        LinearLayout actions = Ui.row(this);
        Button edit = Ui.compactButton(this, "编辑", false);
        Button up = Ui.compactButton(this, "上移", false);
        Button down = Ui.compactButton(this, "下移", false);
        Button delete = Ui.dangerButton(this, "删除");
        edit.setOnClickListener(view -> editItem(template, item));
        up.setOnClickListener(view -> {
            repo.reorderItem(template.id, item.id, -1);
            showItems(repo.template(template.id));
        });
        down.setOnClickListener(view -> {
            repo.reorderItem(template.id, item.id, 1);
            showItems(repo.template(template.id));
        });
        delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setMessage("删除此检查项目？历史记录不会受影响。")
                .setPositiveButton("删除", (dialog, which) -> {
                    repo.deleteTemplateItem(item.id);
                    showItems(repo.template(template.id));
                })
                .setNegativeButton("取消", null).show());
        for (Button button : new Button[]{edit, up, down, delete}) {
            actions.addView(button, Ui.weight(1));
            if (button != delete) actions.addView(Ui.horizontalGap(this, 4));
        }
        card.addView(actions);
        LinearLayout holder = Ui.column(this);
        holder.addView(card);
        holder.addView(Ui.gap(this, 7));
        return holder;
    }

    private void editItem(Template template, TemplateItem item) {
        LinearLayout form = dialogForm();
        EditText category = Ui.input(this, "检查类别（最多12字）");
        EditText content = Ui.input(this, "检查内容（最多100字）");
        EditText standard = Ui.input(this, "检查标准（最多60字）");
        category.setSingleLine(true);
        content.setSingleLine(false);
        content.setMinLines(2);
        content.setMaxLines(4);
        standard.setSingleLine(false);
        standard.setMinLines(2);
        standard.setMaxLines(3);
        category.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_ITEM_CATEGORY)});
        content.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_ITEM_CONTENT)});
        standard.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_ITEM_STANDARD)});
        if (item != null) {
            category.setText(item.category);
            content.setText(item.content);
            standard.setText(item.standard);
        }
        form.addView(category);
        form.addView(Ui.gap(this, 6));
        form.addView(content);
        form.addView(Ui.gap(this, 6));
        form.addView(standard);
        new AlertDialog.Builder(this)
                .setTitle(item == null ? "新增检查项目" : "编辑检查项目")
                .setView(form)
                .setPositiveButton("保存", (dialog, which) -> {
                    String categoryValue = category.getText().toString().trim();
                    String contentValue = content.getText().toString().trim();
                    if (categoryValue.isBlank() || contentValue.isBlank()) {
                        Ui.toast(this, "检查类别和检查内容不能为空");
                        return;
                    }
                    if (item == null && repo.templateItems(template.id).size() >= MAX_TEMPLATE_ITEMS) {
                        Ui.toast(this, "当前模板已达到 9 个检查项目上限");
                        return;
                    }
                    int order = item == null ? repo.templateItems(template.id).size() + 1 : item.order;
                    repo.saveTemplateItem(item == null ? null : item.id, template.id,
                            categoryValue, contentValue, standard.getText().toString().trim(), order);
                    showItems(repo.template(template.id));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private LinearLayout dialogForm() {
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        return form;
    }

    @Override
    public void onBackPressed() {
        if (selectedId != null) showList();
        else super.onBackPressed();
    }
}
