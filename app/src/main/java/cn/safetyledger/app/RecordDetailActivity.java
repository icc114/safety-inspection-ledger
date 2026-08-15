package cn.safetyledger.app;

import android.Manifest;
import android.app.Activity;
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
import android.widget.CheckBox;
import android.widget.TextView;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.Entities.InspectionItem;
import cn.safetyledger.app.data.Entities.Media;
import cn.safetyledger.app.data.Entities.Signature;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;
import cn.safetyledger.app.sync.CloudSyncScheduler;

import java.util.List;

public final class RecordDetailActivity extends Activity {
    private static final int PICK = 610;
    private static final int CAMERA = 612;
    private static final int PERMISSION = 613;
    private static final int SIGN = 614;

    private LedgerRepository repo;
    private Inspection model;
    private LinearLayout mediaBox;
    private LinearLayout signaturesBox;
    private EditText rectification;
    private EditText recheck;
    private CheckBox confirmed;
    private Uri cameraUri;
    private String pendingCategory = "RECTIFICATION";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        repo = new LedgerRepository(this);
        model = repo.inspection(getIntent().getStringExtra("inspection_id"));
        if (model == null) {
            finish();
            return;
        }
        render();
    }

    private void render() {
        model = repo.inspection(model.id);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(topBar());
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 28));
        content.addView(summaryCard());
        content.addView(Ui.gap(this, 12));
        content.addView(itemsCard());
        content.addView(Ui.gap(this, 12));
        content.addView(photoCard());
        content.addView(Ui.gap(this, 12));
        content.addView(signatureCard());
        if (hasProblem()) {
            content.addView(Ui.gap(this, 12));
            content.addView(rectificationCard());
        }
        content.addView(Ui.gap(this, 14));
        Button delete = Ui.secondaryButton(this, "移入回收站");
        delete.setTextColor(Ui.DANGER);
        delete.setOnClickListener(view -> {
            repo.softDelete(model.id);
            CloudSyncScheduler.scheduleImmediate(this);
            Ui.toast(this, "已移入回收站；正在后台同步");
            finish();
        });
        content.addView(delete);
        root.addView(content);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        bar.setBackgroundColor(Ui.BLUE);
        Button back = Ui.secondaryButton(this, "‹");
        back.setTextSize(22);
        back.setOnClickListener(view -> finish());
        TextView title = Ui.text(this, "检查记录详情", 20, true);
        title.setTextColor(Color.WHITE);
        TextView status = Ui.text(this, status(model.status), 14, true);
        status.setTextColor(Ui.BLUE_DARK);
        status.setBackground(Ui.shape(this, Color.WHITE, Color.TRANSPARENT, 18));
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        bar.addView(title, Ui.weight(1));
        bar.addView(status);
        return bar;
    }

    private LinearLayout summaryCard() {
        LinearLayout card = Ui.card(this);
        TextView title = Ui.text(this, formTitle(model.templateName), 22, true);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        card.addView(Ui.gap(this, 7));
        card.addView(infoRow("检查日期", model.date));
        card.addView(Ui.divider(this));
        card.addView(infoRow("检查地点", model.location));
        return card;
    }

    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = Ui.row(this);
        TextView caption = Ui.text(this, label, 15, true);
        caption.setTextColor(Ui.MUTED);
        row.addView(caption, new LinearLayout.LayoutParams(Ui.dp(this, 92),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(Ui.text(this, value, 16, true), Ui.weight(1));
        return row;
    }

    private LinearLayout itemsCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "1", "检查事项", "“否”项包含现场问题和整改要求"));
        card.addView(Ui.gap(this, 6));
        for (InspectionItem item : model.items) {
            LinearLayout row = Ui.row(this);
            TextView description = Ui.text(this, item.order + ". " + item.category + "\n" + item.content, 14, false);
            TextView result = Ui.text(this, result(item.result), 15, true);
            result.setGravity(Gravity.CENTER);
            result.setTextColor("FAIL".equals(item.result) ? Ui.DANGER : Ui.BLUE_DARK);
            result.setBackground(Ui.shape(this,
                    "FAIL".equals(item.result) ? Color.rgb(255, 239, 239) : Ui.BLUE_PALE,
                    Color.TRANSPARENT, 12));
            row.addView(description, Ui.weight(1));
            row.addView(result, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 40)));
            card.addView(row);
            if ("FAIL".equals(item.result)) {
                TextView problem = Ui.text(this, "现场情况、问题及整改要求：\n" + item.problem, 14, false);
                problem.setTextColor(Color.rgb(145, 74, 18));
                problem.setBackground(Ui.shape(this, Color.rgb(255, 249, 240),
                        Color.rgb(244, 190, 110), 8));
                card.addView(problem);
            }
            card.addView(Ui.divider(this));
        }
        return card;
    }

    private LinearLayout photoCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "2", "检查照片", "保存记录后仍可补拍、补传和预览"));
        LinearLayout actions = Ui.row(this);
        Button camera = Ui.button(this, "+ 补拍检查照片");
        Button gallery = Ui.secondaryButton(this, "从相册补传");
        camera.setOnClickListener(view -> capture("SCENE"));
        gallery.setOnClickListener(view -> pick("SCENE"));
        actions.addView(camera, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 7));
        actions.addView(gallery, Ui.weight(1));
        card.addView(actions);
        card.addView(Ui.gap(this, 7));
        mediaBox = Ui.column(this);
        card.addView(mediaBox);
        showMedia();
        return card;
    }

    private LinearLayout signatureCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "3", "现场签名", "漏签可补签，已有签名可重新签写"));
        signaturesBox = Ui.column(this);
        card.addView(signaturesBox);
        showSignatures();
        return card;
    }

    private void showSignatures() {
        if (signaturesBox == null) return;
        signaturesBox.removeAllViews();
        List<Signature> signatures = repo.signatures(model.id);
        String[][] roles = {
                {"INSPECTOR1", "检查人签名1"},
                {"INSPECTOR2", "检查人签名2"},
                {"INSPECTEE", "被检查人签名"}
        };
        for (String[] role : roles) {
            Signature found = null;
            for (Signature signature : signatures) if (role[0].equals(signature.role)) found = signature;
            LinearLayout row = Ui.row(this);
            row.addView(Ui.text(this, role[1], 14, true), Ui.weight(1));
            if (found != null) {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(MediaService.decodeThumbnail(found.path, 500));
                row.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 58)));
            }
            boolean signed = found != null;
            Button action = Ui.secondaryButton(this, signed ? "重签" : "+ 补签");
            action.setOnClickListener(view -> sign(role[0]));
            row.addView(Ui.horizontalGap(this, 5));
            row.addView(action, new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 40)));
            signaturesBox.addView(row);
            signaturesBox.addView(Ui.divider(this));
        }
    }

    private LinearLayout rectificationCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "4", "整改记录", "整改完成后补录照片并确认"));
        rectification = Ui.input(this, "填写具体整改情况（最多70字）");
        rectification.setFilters(new InputFilter[]{new InputFilter.LengthFilter(70)});
        rectification.setText(model.rectification);
        rectification.setMinLines(3);
        rectification.setGravity(Gravity.TOP);
        card.addView(rectification);
        card.addView(Ui.gap(this, 8));
        LinearLayout actions = Ui.row(this);
        Button camera = Ui.button(this, "+ 拍摄整改照片");
        Button gallery = Ui.secondaryButton(this, "选择整改照片");
        camera.setOnClickListener(view -> capture("RECTIFICATION"));
        gallery.setOnClickListener(view -> pick("RECTIFICATION"));
        actions.addView(camera, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 7));
        actions.addView(gallery, Ui.weight(1));
        card.addView(actions);

        recheck = Ui.input(this, "复查说明（可选）");
        recheck.setText(model.recheck);
        recheck.setMinLines(2);
        card.addView(Ui.gap(this, 8));
        card.addView(recheck);
        confirmed = new CheckBox(this);
        confirmed.setText("整改确认：已整改完成");
        confirmed.setTextSize(16);
        confirmed.setTextColor(Ui.TEXT);
        confirmed.setChecked("RECTIFIED".equals(model.status) || "COMPLETED".equals(model.status));
        confirmed.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        card.addView(confirmed);
        Button save = Ui.button(this, "保存整改记录");
        save.setOnClickListener(view -> saveRectification());
        card.addView(save);
        return card;
    }

    private boolean hasProblem() {
        for (InspectionItem item : model.items) if ("FAIL".equals(item.result)) return true;
        return false;
    }

    private void saveRectification() {
        model.rectification = rectification.getText().toString().trim();
        model.recheck = recheck.getText().toString().trim();
        if (model.rectification.isBlank()) {
            Ui.toast(this, "请填写整改情况");
            return;
        }
        if (confirmed.isChecked() && !hasRectificationPhoto()) {
            Ui.toast(this, "整改确认前请至少补录一张整改照片");
            return;
        }
        model.status = confirmed.isChecked() ? "RECTIFIED" : "RECTIFYING";
        repo.saveInspection(model);
        CloudSyncScheduler.scheduleImmediate(this);
        Ui.toast(this, confirmed.isChecked() ? "整改已确认完成；正在后台同步" : "整改记录已保存；正在后台同步");
        render();
    }

    private boolean hasRectificationPhoto() {
        for (Media media : repo.media(model.id)) {
            if ("RECTIFICATION".equals(media.category)) return true;
        }
        return false;
    }

    private void pick(String category) {
        pendingCategory = category;
        startActivityForResult(Ui.photoPickerIntent(), PICK);
    }

    private void capture(String category) {
        pendingCategory = category;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION);
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,
                ("SCENE".equals(pendingCategory) ? "inspection-" : "rectification-")
                        + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, cameraUri), CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == PERMISSION && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) capture(pendingCategory);
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
        try {
            if (request == PICK && data != null) {
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        importPhoto(data.getClipData().getItemAt(i).getUri(), false);
                    }
                } else if (data.getData() != null) {
                    importPhoto(data.getData(), false);
                }
            } else if (request == CAMERA && cameraUri != null) {
                importPhoto(cameraUri, true);
                getContentResolver().delete(cameraUri, null, null);
            } else if (request == SIGN) {
                showSignatures();
                CloudSyncScheduler.scheduleImmediate(this);
                Ui.toast(this, "签名已保存");
            }
        } catch (Exception error) {
            Ui.toast(this, "操作失败：" + error.getMessage());
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

    private void importPhoto(Uri uri, boolean capturedNow) throws Exception {
        Media media = new MediaService(this).importAndWatermark(uri, model.id, null,
                pendingCategory, model.location, capturedNow ? lastLocation() : null, capturedNow);
        repo.addMedia(media);
        showMedia();
        CloudSyncScheduler.scheduleImmediate(this);
    }

    private void showMedia() {
        if (mediaBox == null) return;
        mediaBox.removeAllViews();
        model.media.clear();
        model.media.addAll(repo.media(model.id));
        for (Media media : model.media) {
            LinearLayout row = Ui.row(this);
            ImageView image = new ImageView(this);
            image.setImageBitmap(MediaService.decodeThumbnail(media.localPath, 360));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription("点击放大照片");
            View.OnClickListener preview = view -> Ui.previewPhoto(this, media.localPath);
            image.setOnClickListener(preview);
            row.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 66)));
            TextView label = Ui.text(this, category(media.category) + " · 点击任意位置查看大图", 14, true);
            if ("RECTIFICATION".equals(media.category)) label.setTextColor(Ui.BLUE_DARK);
            label.setOnClickListener(preview);
            row.addView(label, Ui.weight(1));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(preview);
            mediaBox.addView(row);
            mediaBox.addView(Ui.divider(this));
        }
        if (model.media.isEmpty()) {
            TextView empty = Ui.text(this, "尚未上传检查照片", 14, false);
            empty.setTextColor(Ui.MUTED);
            mediaBox.addView(empty);
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

    private String result(String result) {
        return "PASS".equals(result) ? "是" : "FAIL".equals(result) ? "否" : "未选择";
    }

    private String category(String category) {
        return switch (category) {
            case "RECTIFICATION" -> "整改照片";
            case "RECHECK" -> "复查照片";
            default -> "检查照片";
        };
    }
    private String formTitle(String value) {
        String name = value == null || value.isBlank() ? "安全检查" : value.trim();
        if (name.endsWith("记录表")) return name;
        if (name.endsWith("记录")) return name + "表";
        return name + "记录表";
    }

}
