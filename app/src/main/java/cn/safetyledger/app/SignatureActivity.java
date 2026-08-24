package cn.safetyledger.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.gcacace.signaturepad.views.SignaturePad;

import cn.safetyledger.app.data.Entities.Signature;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.UUID;

public final class SignatureActivity extends Activity {
    private static final float PEN_MIN_WIDTH_DP = 3f;
    private static final float PEN_MAX_WIDTH_DP = 6f;
    private static final float VELOCITY_FILTER_WEIGHT = 0.82f;
    private static final int SIGNATURE_PADDING_DP = 10;

    private SignaturePad pad;
    private String inspectionId;
    private String role;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        hideSystemBars();
        inspectionId = getIntent().getStringExtra("inspection_id");
        role = getIntent().getStringExtra("role");

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout controls = Ui.row(this);
        // Keep toolbar controls away from waterfall/curved screen edges in landscape.
        controls.setPadding(Ui.dp(this, 26), Ui.dp(this, 2), Ui.dp(this, 26), Ui.dp(this, 2));
        controls.setBackgroundColor(Ui.BLUE);
        TextView title = Ui.text(this, "‹  " + roleName(role) + "（下方签名）", 14, true);
        title.setTextColor(Color.WHITE);
        title.setOnClickListener(view -> finish());
        Button clear = Ui.secondaryButton(this, "清空");
        Button save = Ui.secondaryButton(this, "确认");
        clear.setTextSize(12);
        save.setTextSize(12);
        clear.setOnClickListener(view -> pad.clear());
        save.setOnClickListener(view -> save());
        controls.addView(title, Ui.weight(1));
        controls.addView(clear, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 30)));
        controls.addView(Ui.horizontalGap(this, 4));
        controls.addView(save, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 30)));
        root.addView(controls, new LinearLayout.LayoutParams(-1, Ui.dp(this, 34)));

        pad = new SignaturePad(this, null);
        pad.setPenColor(Color.rgb(15, 23, 42));
        pad.setMinWidth(PEN_MIN_WIDTH_DP);
        pad.setMaxWidth(PEN_MAX_WIDTH_DP);
        pad.setVelocityFilterWeight(VELOCITY_FILTER_WEIGHT);
        pad.setBackgroundColor(Color.WHITE);
        root.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private String roleName(String value) {
        return "INSPECTOR1".equals(value) ? "检查人1签名"
                : "INSPECTOR2".equals(value) ? "检查人2签名" : "被检查人签名";
    }

    private void save() {
        if (pad.isEmpty()) {
            Ui.toast(this, "请先签名");
            return;
        }
        try {
            File directory = new File(getFilesDir(), "business_media/" + inspectionId);
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("无法创建签名目录");
            }
            // A new immutable file avoids an older peer snapshot overwriting a newer signature.
            File file = new File(directory, "signature-" + role + "-"
                    + System.currentTimeMillis() + ".png");

            Bitmap cropped = pad.getTransparentSignatureBitmap(true);
            if (cropped == null) throw new IllegalStateException("未读取到有效签名");
            Bitmap bitmap = addTransparentPadding(cropped);
            cropped.recycle();
            try (OutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("签名图片压缩失败");
                }
            } finally {
                bitmap.recycle();
            }

            Signature signature = new Signature();
            signature.id = UUID.nameUUIDFromBytes((inspectionId + role).getBytes()).toString();
            signature.inspectionId = inspectionId;
            signature.role = role;
            signature.path = file.getAbsolutePath();
            signature.sha256 = MediaService.sha256(file);
            new LedgerRepository(this).saveSignature(signature);
            setResult(RESULT_OK, new Intent().putExtra("role", role));
            finish();
        } catch (Exception error) {
            Ui.toast(this, "签名保存失败：" + error.getMessage());
        }
    }

    private Bitmap addTransparentPadding(Bitmap source) {
        int padding = Ui.dp(this, SIGNATURE_PADDING_DP);
        Bitmap padded = Bitmap.createBitmap(
                source.getWidth() + padding * 2,
                source.getHeight() + padding * 2,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(padded);
        canvas.drawBitmap(source, padding, padding, null);
        return padded;
    }
}
