package cn.safetyledger.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import cn.safetyledger.app.data.Entities.Signature;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.UUID;

public final class SignatureActivity extends Activity {
    private SignaturePad pad;
    private String inspectionId;
    private String role;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        inspectionId = getIntent().getStringExtra("inspection_id");
        role = getIntent().getStringExtra("role");

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout controls = Ui.row(this);
        controls.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
        controls.setBackgroundColor(Ui.BLUE);
        TextView title = Ui.text(this, "‹  " + roleName(role) + " · 请在下方签名", 16, true);
        title.setTextColor(Color.WHITE);
        title.setOnClickListener(view -> finish());
        Button clear = Ui.secondaryButton(this, "清空");
        Button save = Ui.secondaryButton(this, "确认");
        clear.setTextSize(14);
        save.setTextSize(14);
        clear.setOnClickListener(view -> pad.clear());
        save.setOnClickListener(view -> save());
        controls.addView(title, Ui.weight(1));
        controls.addView(clear, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 38)));
        controls.addView(Ui.horizontalGap(this, 6));
        controls.addView(save, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 38)));
        root.addView(controls, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        pad = new SignaturePad(this);
        root.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private String roleName(String value) {
        return "INSPECTOR1".equals(value) ? "检查人1签名"
                : "INSPECTOR2".equals(value) ? "检查人2签名" : "被检查人签名";
    }

    private void save() {
        if (pad.empty) {
            Ui.toast(this, "请先签名");
            return;
        }
        try {
            File directory = new File(getFilesDir(), "business_media/" + inspectionId);
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建签名目录");
            File file = new File(directory, "signature-" + role + ".png");
            Bitmap bitmap = pad.bitmap();
            try (OutputStream output = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            bitmap.recycle();
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

    private static final class SignaturePad extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean empty = true;
        private float lastX;
        private float lastY;

        SignaturePad(android.content.Context context) {
            super(context);
            paint.setColor(Color.rgb(15, 23, 42));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(context, 5));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setBackgroundColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(path, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                path.moveTo(x, y);
                lastX = x;
                lastY = y;
                empty = false;
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2);
                lastX = x;
                lastY = y;
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                performClick();
            }
            invalidate();
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        void clear() {
            path.reset();
            empty = true;
            invalidate();
        }

        Bitmap bitmap() {
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            canvas.drawPath(path, paint);
            return bitmap;
        }
    }
}
