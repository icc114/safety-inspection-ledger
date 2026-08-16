package cn.safetyledger.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.Entities.InspectionItem;
import cn.safetyledger.app.data.Entities.Media;
import cn.safetyledger.app.data.Entities.Signature;
import cn.safetyledger.app.data.Entities.Template;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;
import cn.safetyledger.app.sync.CloudSyncScheduler;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int CAMERA = 501;
    private static final int GALLERY = 502;
    private static final int SIGN = 503;
    private static final int PERMISSIONS = 504;

    private LedgerRepository repo;
    private Inspection model;
    private LinearLayout itemsBox;
    private LinearLayout mediaBox;
    private LinearLayout signaturesBox;
    private TextView answeredCount;
    private TextView photoCount;
    private Spinner templateSpinner;
    private List<Template> activeTemplates;
    private boolean bindingTemplate;
    private boolean deletingDraft;
    private final Map<String, EditText> fields = new HashMap<>();
    private final Map<String, EditText> problemFields = new HashMap<>();
    private final Map<String, LinearLayout> problemAreas = new HashMap<>();
    private Uri cameraUri;
    private String pendingCategory = "SCENE";
    private String pendingItemId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        repo = new LedgerRepository(this);
        String id = getIntent().getStringExtra("inspection_id");
        if (id == null) id = repo.setting("current_draft", "");
        model = id.isBlank() ? null : repo.inspection(id);
        if (model == null || model.deletedAt != null || !"DRAFT".equals(model.status)) {
            activeTemplates = usableTemplates();
            if (activeTemplates.isEmpty()) {
                Ui.toast(this, "请先新建并启用检查模板");
                Ui.start(this, TemplateActivity.class);
                finish();
                return;
            }
            model = repo.newInspection(activeTemplates.get(0).id);
        }
        repo.putSetting("current_draft", model.id);
        render();
    }

    private void render() {
        fields.clear();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        bar.setBackgroundColor(Ui.BLUE);
        TextView back = Ui.backButton(this);
        TextView topTitle = Ui.text(this, "本地检查表\n检查填报", 20, true);
        topTitle.setTextColor(Color.WHITE);
        Button topSave = Ui.secondaryButton(this, "保存");
        topSave.setOnClickListener(view -> save());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        bar.addView(topTitle, Ui.weight(1));
        bar.addView(topSave, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 46)));
        root.addView(bar);

        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 26));
        content.addView(basicCard());
        content.addView(Ui.gap(this, 12));
        content.addView(itemsCard());
        content.addView(Ui.gap(this, 12));
        content.addView(photoCard());
        content.addView(Ui.gap(this, 12));
        content.addView(signatureCard());
        content.addView(Ui.gap(this, 14));
        Button save = Ui.button(this, "保存检查记录");
        save.setTextSize(18);
        save.setOnClickListener(view -> save());
        content.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)));
        content.addView(Ui.gap(this, 8));
        Button deleteDraft = Ui.dangerButton(this, "删除草稿（移入回收站）");
        deleteDraft.setTextSize(15);
        deleteDraft.setOnClickListener(view -> confirmDeleteDraft());
        content.addView(deleteDraft, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 48)));
        root.addView(content);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout basicCard() {
        LinearLayout card = Ui.card(this);
        TextView title = Ui.text(this, formTitle(model.templateName), 23, true);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        card.addView(Ui.gap(this, 8));
        card.addView(templatePicker());
        card.addView(Ui.gap(this, 8));
        card.addView(labeledInput("检查日期", model.date, "date", true));
        card.addView(Ui.gap(this, 8));
        card.addView(labeledInput("检查地点", model.location, "location", false));
        return card;
    }

    private View templatePicker() {
        LinearLayout box = Ui.column(this);
        TextView caption = Ui.text(this, "检查类型 / 检查模板", 14, true);
        caption.setTextColor(Ui.MUTED);
        activeTemplates = usableTemplates();
        String[] names = new String[activeTemplates.size()];
        int selected = 0;
        for (int i = 0; i < activeTemplates.size(); i++) {
            names[i] = activeTemplates.get(i).name;
            if (activeTemplates.get(i).id.equals(model.templateId)) selected = i;
        }
        templateSpinner = new Spinner(this);
        templateSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        templateSpinner.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 10));
        templateSpinner.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        bindingTemplate = true;
        templateSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                  int position, long id) {
                if (bindingTemplate) { bindingTemplate = false; return; }
                Template chosen = activeTemplates.get(position);
                if (chosen.id.equals(model.templateId)) return;
                switchTemplate(chosen);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        templateSpinner.setSelection(selected, false);
        templateSpinner.post(() -> bindingTemplate = false);
        box.addView(caption);
        box.addView(templateSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 50)));
        return box;
    }

    private void switchTemplate(Template template) {
        Runnable replace = () -> {
            new MediaService(this).deleteInspectionMedia(model.id);
            repo.discardDraft(model.id);
            model = repo.newInspection(template.id);
            repo.putSetting("current_draft", model.id);
            render();
        };
        if (hasDraftInput()) {
            new AlertDialog.Builder(this)
                    .setTitle("切换检查模板")
                    .setMessage("切换会放弃当前尚未保存的勾选、问题、照片和签名。是否继续？")
                    .setPositiveButton("继续切换", (dialog, which) -> replace.run())
                    .setNegativeButton("取消", (dialog, which) -> restoreTemplateSelection())
                    .setOnCancelListener(dialog -> restoreTemplateSelection())
                    .show();
        } else replace.run();
    }

    private boolean hasDraftInput() {
        for (InspectionItem item : model.items) {
            if ("PASS".equals(item.result) || "FAIL".equals(item.result) || !item.problem.isBlank()) return true;
        }
        return !repo.media(model.id).isEmpty() || !repo.signatures(model.id).isEmpty()
                || (fields.containsKey("location") && !value("location").isBlank());
    }

    private void restoreTemplateSelection() {
        if (templateSpinner == null) return;
        for (int i = 0; i < activeTemplates.size(); i++) {
            if (activeTemplates.get(i).id.equals(model.templateId)) {
                bindingTemplate = true;
                templateSpinner.setSelection(i, false);
                templateSpinner.post(() -> bindingTemplate = false);
                break;
            }
        }
    }

    private List<Template> usableTemplates() {
        List<Template> usable = new ArrayList<>();
        for (Template template : repo.templates(false)) {
            if (!template.items.isEmpty()) usable.add(template);
        }
        return usable;
    }

    private View labeledInput(String label, String value, String key, boolean date) {
        LinearLayout box = Ui.column(this);
        TextView caption = Ui.text(this, label, 14, true);
        caption.setTextColor(Ui.MUTED);
        EditText input = Ui.input(this, date ? "选择检查日期" : "请输入检查地点");
        input.setText(value);
        input.setSingleLine(true);
        fields.put(key, input);
        if (date) {
            input.setFocusable(false);
            input.setOnClickListener(view -> showDatePicker(input));
        }
        box.addView(caption);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 50)));
        return box;
    }

    private void showDatePicker(EditText input) {
        LocalDate value;
        try {
            value = LocalDate.parse(input.getText().toString());
        } catch (Exception ignored) {
            value = LocalDate.now();
        }
        new DatePickerDialog(this, (picker, year, month, day) -> input.setText(
                String.format(Locale.CHINA, "%04d-%02d-%02d", year, month + 1, day)),
                value.getYear(), value.getMonthValue() - 1, value.getDayOfMonth()).show();
    }

    private LinearLayout itemsCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout heading = Ui.row(this);
        heading.addView(Ui.sectionTitle(this, "1", "检查事项", "逐项选择“是”或“否”"), Ui.weight(1));
        answeredCount = Ui.text(this, "0/" + model.items.size(), 14, true);
        answeredCount.setTextColor(Ui.BLUE_DARK);
        answeredCount.setBackground(Ui.shape(this, Ui.BLUE_PALE, Color.TRANSPARENT, 18));
        heading.addView(answeredCount);
        card.addView(heading);
        card.addView(Ui.gap(this, 8));

        LinearLayout header = Ui.row(this);
        header.setBackgroundColor(Color.rgb(219, 231, 249));
        TextView left = Ui.text(this, "检查类别与标准", 14, true);
        TextView right = Ui.text(this, "检查结果", 14, true);
        right.setGravity(Gravity.CENTER);
        header.addView(left, Ui.weight(3));
        header.addView(right, Ui.weight(1));
        card.addView(header);

        itemsBox = Ui.column(this);
        card.addView(itemsBox);
        renderItems();
        return card;
    }

    private void renderItems() {
        itemsBox.removeAllViews();
        problemFields.clear();
        problemAreas.clear();
        for (InspectionItem item : model.items) {
            LinearLayout itemBlock = Ui.column(this);
            LinearLayout row = Ui.row(this);

            TextView category = Ui.text(this, item.category + "\n" + item.order, 14, true);
            category.setGravity(Gravity.CENTER);
            category.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 0));
            row.addView(category, new LinearLayout.LayoutParams(Ui.dp(this, 78),
                    ViewGroup.LayoutParams.MATCH_PARENT));

            String description = item.content;
            if (!item.standard.isBlank() && !item.standard.equals(item.content)) {
                description += "\n标准：" + item.standard;
            }
            TextView content = Ui.text(this, description, 14, false);
            content.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 0));
            row.addView(content, Ui.weight(1));

            LinearLayout choices = Ui.column(this);
            choices.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            choices.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 0));
            Button yes = Ui.choiceButton(this, "是", "PASS".equals(item.result));
            Button no = Ui.choiceButton(this, "否", "FAIL".equals(item.result));
            yes.setOnClickListener(view -> setResult(item, yes, no, "PASS"));
            no.setOnClickListener(view -> setResult(item, yes, no, "FAIL"));
            choices.addView(yes, new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 38)));
            choices.addView(Ui.gap(this, 5));
            choices.addView(no, new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 38)));
            row.addView(choices);
            itemBlock.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout problemArea = Ui.column(this);
            problemArea.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 10));
            problemArea.setBackground(Ui.shape(this, Color.rgb(255, 249, 240), Color.rgb(244, 190, 110), 0));
            TextView problemTitle = Ui.text(this, "现场情况、问题及整改要求", 14, true);
            problemTitle.setTextColor(Color.rgb(155, 83, 20));
            EditText problem = Ui.input(this, "请填写发现的问题和整改要求（最多40字）");
            problem.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
            problem.setText(item.problem);
            problem.setMinLines(2);
            problem.setGravity(Gravity.TOP);
            problemFields.put(item.id, problem);
            problemArea.addView(problemTitle);
            problemArea.addView(problem);
            problemArea.setVisibility("FAIL".equals(item.result) ? View.VISIBLE : View.GONE);
            problemAreas.put(item.id, problemArea);
            itemBlock.addView(problemArea);
            itemsBox.addView(itemBlock);
        }
        refreshAnswered();
    }

    private void setResult(InspectionItem item, Button yes, Button no, String result) {
        item.result = result;
        Ui.styleChoice(this, yes, "PASS".equals(result));
        Ui.styleChoice(this, no, "FAIL".equals(result));
        LinearLayout problem = problemAreas.get(item.id);
        if (problem != null) problem.setVisibility("FAIL".equals(result) ? View.VISIBLE : View.GONE);
        refreshAnswered();
    }

    private void refreshAnswered() {
        if (answeredCount == null) return;
        int count = 0;
        for (InspectionItem item : model.items) {
            if ("PASS".equals(item.result) || "FAIL".equals(item.result)) count++;
        }
        answeredCount.setText(count + "/" + model.items.size());
    }

    private LinearLayout photoCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout heading = Ui.row(this);
        heading.addView(Ui.sectionTitle(this, "2", "检查照片", "自动添加时间和地点水印"), Ui.weight(1));
        photoCount = Ui.text(this, "0 张", 14, true);
        photoCount.setTextColor(Ui.BLUE_DARK);
        photoCount.setBackground(Ui.shape(this, Ui.BLUE_PALE, Color.TRANSPARENT, 18));
        heading.addView(photoCount);
        card.addView(heading);
        card.addView(Ui.gap(this, 8));
        LinearLayout actions = Ui.row(this);
        Button camera = Ui.button(this, "+ 拍照");
        Button gallery = Ui.secondaryButton(this, "从相册选择");
        camera.setOnClickListener(view -> capture("SCENE", null));
        gallery.setOnClickListener(view -> pick("SCENE", null));
        actions.addView(camera, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 8));
        actions.addView(gallery, Ui.weight(1));
        card.addView(actions);
        mediaBox = Ui.column(this);
        card.addView(mediaBox);
        renderMedia();
        return card;
    }

    private LinearLayout signatureCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "3", "现场签名", "签名时自动切换为横屏"));
        card.addView(Ui.gap(this, 6));
        signaturesBox = Ui.column(this);
        card.addView(signaturesBox);
        renderSignatures();
        return card;
    }

    private void renderSignatures() {
        if (signaturesBox == null) return;
        signaturesBox.removeAllViews();
        List<Signature> signatures = repo.signatures(model.id);
        String[][] roles = {
                {"INSPECTOR1", "检查人签名1"},
                {"INSPECTOR2", "检查人签名2"},
                {"INSPECTEE", "被检查人签名"}
        };
        for (String[] role : roles) {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            boolean signed = false;
            for (Signature signature : signatures) {
                if (role[0].equals(signature.role)) signed = true;
            }
            TextView label = Ui.text(this, role[1] + "\n" + (signed ? "已签名，可重新签写" : "点击右侧开始签名"), 15, true);
            if (signed) label.setTextColor(Ui.BLUE_DARK);
            Button button = Ui.secondaryButton(this, signed ? "重签" : "+ 签名");
            button.setOnClickListener(view -> sign(role[0]));
            row.addView(label, Ui.weight(1));
            row.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 94), Ui.dp(this, 46)));
            signaturesBox.addView(row);
            signaturesBox.addView(Ui.divider(this));
        }
    }

    private void capture(String category, String itemId) {
        pendingCategory = category;
        pendingItemId = itemId;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS);
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "inspection-" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        startActivityForResult(intent, CAMERA);
    }

    private void pick(String category, String itemId) {
        pendingCategory = category;
        pendingItemId = itemId;
        startActivityForResult(Ui.photoPickerIntent(), GALLERY);
    }

    private void sign(String role) {
        startActivityForResult(new Intent(this, SignatureActivity.class)
                .putExtra("inspection_id", model.id)
                .putExtra("role", role), SIGN);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK) return;
        if (request == CAMERA && cameraUri != null) {
            importPhoto(cameraUri, true);
            getContentResolver().delete(cameraUri, null, null);
        } else if (request == GALLERY && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    importPhoto(data.getClipData().getItemAt(i).getUri(), false);
                }
            } else if (data.getData() != null) {
                importPhoto(data.getData(), false);
            }
        } else if (request == SIGN) {
            renderSignatures();
            CloudSyncScheduler.scheduleImmediate(this);
            Ui.toast(this, "签名已保存");
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == PERMISSIONS && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            capture(pendingCategory, pendingItemId);
        }
    }

    private Location lastLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager manager = getSystemService(LocationManager.class);
        Location best = null;
        for (String provider : manager.getProviders(true)) {
            Location value = manager.getLastKnownLocation(provider);
            if (value != null && (best == null || value.getAccuracy() < best.getAccuracy())) best = value;
        }
        return best;
    }

    private void importPhoto(Uri uri, boolean capturedNow) {
        syncFields();
        try {
            Media media = new MediaService(this).importAndWatermark(uri, model.id, pendingItemId,
                    pendingCategory, model.location, capturedNow ? lastLocation() : null, capturedNow);
            repo.addMedia(media);
            renderMedia();
            CloudSyncScheduler.scheduleImmediate(this);
        } catch (Exception error) {
            Ui.toast(this, "照片处理失败：" + error.getMessage());
        }
    }

    private void renderMedia() {
        if (mediaBox == null) return;
        mediaBox.removeAllViews();
        List<Media> media = repo.media(model.id);
        if (photoCount != null) photoCount.setText(media.size() + " 张");
        for (Media item : media) {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 7), 0, 0);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(MediaService.decodeThumbnail(item.localPath, 320));
            image.setContentDescription("点击放大检查照片");
            View.OnClickListener preview = view -> Ui.previewPhoto(this, item.localPath);
            image.setOnClickListener(preview);
            row.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 64)));
            TextView label = Ui.text(this, "检查照片 · 点击任意位置查看大图\n"
                    + new File(item.localPath).getName(), 13, false);
            label.setOnClickListener(preview);
            row.addView(label, Ui.weight(1));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(preview);
            mediaBox.addView(row);
        }
    }

    private void syncFields() {
        if (fields.containsKey("date")) model.date = value("date");
        if (fields.containsKey("location")) model.location = value("location");
        // Keep snapshot fields for upgrade compatibility, but the simplified form no longer asks for them.
        model.conclusion = "";
        model.advice = "";
        model.responsible = "";
        model.deadline = "";
        for (InspectionItem item : model.items) {
            EditText problem = problemFields.get(item.id);
            if (problem != null) item.problem = problem.getText().toString().trim();
        }
    }

    private String value(String key) {
        return fields.get(key).getText().toString().trim();
    }

    private void confirmDeleteDraft() {
        syncFields();
        new AlertDialog.Builder(this)
                .setTitle("删除草稿")
                .setMessage("该草稿将移入回收站，包含已填写的检查结果、问题、照片和签名。误删后可在“基础设置 → 回收站”恢复。")
                .setPositiveButton("删除草稿", (dialog, which) -> {
                    deletingDraft = true;
                    repo.saveInspection(model);
                    repo.softDelete(model.id);
                    if (model.id.equals(repo.setting("current_draft", ""))) {
                        repo.putSetting("current_draft", "");
                    }
                    Ui.toast(this, "草稿已移入回收站");
                    startActivity(new Intent(this, LedgerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void save() {
        syncFields();
        boolean incomplete = false;
        boolean hasProblem = false;
        for (InspectionItem item : model.items) {
            if (!"PASS".equals(item.result) && !"FAIL".equals(item.result)) incomplete = true;
            if ("FAIL".equals(item.result)) {
                hasProblem = true;
                if (item.problem.isBlank()) {
                    Ui.toast(this, "选择“否”时必须填写“现场情况、问题及整改要求”");
                    return;
                }
            }
        }
        if (incomplete) {
            Ui.toast(this, "请为每个检查项目选择“是”或“否”");
            return;
        }
        if (model.date.isBlank()) {
            Ui.toast(this, "请选择检查日期");
            return;
        }
        if (model.location.isBlank()) {
            Ui.toast(this, "请填写检查地点");
            return;
        }
        model.status = hasProblem ? "PENDING_RECTIFICATION" : "COMPLETED";
        repo.saveInspection(model);
        repo.putSetting("current_draft", "");
        CloudSyncScheduler.scheduleImmediate(this);
        Ui.toast(this, hasProblem ? "已保存，待补录整改记录；正在后台同步" : "检查记录已保存；正在后台同步");
        startActivity(new Intent(this, RecordDetailActivity.class)
                .putExtra("inspection_id", model.id));
        finish();
    }

    @Override
    protected void onPause() {
        if (!deletingDraft && model != null && "DRAFT".equals(model.status)) {
            try {
                syncFields();
                repo.saveInspection(model);
                repo.putSetting("current_draft", model.id);
                CloudSyncScheduler.scheduleImmediate(this);
            } catch (RuntimeException ignored) {
                // Keep navigation responsive; an explicit save still reports validation errors.
            }
        }
        super.onPause();
    }
    private String formTitle(String value) {
        String name = value == null || value.isBlank() ? "安全检查" : value.trim();
        if (name.endsWith("记录表")) return name;
        if (name.endsWith("记录")) return name + "表";
        return name + "记录表";
    }

}
