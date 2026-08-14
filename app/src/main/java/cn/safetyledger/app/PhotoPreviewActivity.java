package cn.safetyledger.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen local photo preview with pinch and double-tap zoom. */
public final class PhotoPreviewActivity extends Activity {
    private Bitmap bitmap;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.getInsetsController().hide(WindowInsets.Type.statusBars()
                    | WindowInsets.Type.navigationBars());
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        String path = getIntent().getStringExtra("photo_path");
        bitmap = decodeForScreen(path);
        if (bitmap == null) {
            Ui.toast(this, "照片文件无法读取");
            finish();
            return;
        }

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3));
        Button back = Ui.secondaryButton(this, "‹ 返回");
        back.setOnClickListener(view -> finish());
        TextView hint = Ui.text(this, "双击或双指缩放照片", 13, false);
        hint.setTextColor(Color.WHITE);
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 38)));
        top.addView(hint, Ui.weight(1));
        root.addView(top, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        ZoomImageView image = new ZoomImageView();
        image.setImageBitmap(bitmap);
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private Bitmap decodeForScreen(String path) {
        if (path == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int target = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 2;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > target) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, options);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private final class ZoomImageView extends ImageView implements View.OnTouchListener {
        private final Matrix transform = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private float relativeScale = 1f;
        private float lastX;
        private float lastY;

        ZoomImageView() {
            super(PhotoPreviewActivity.this);
            setScaleType(ScaleType.MATRIX);
            setBackgroundColor(Color.BLACK);
            scaleDetector = new ScaleGestureDetector(PhotoPreviewActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScale(ScaleGestureDetector detector) {
                            float target = Math.max(1f, Math.min(5f,
                                    relativeScale * detector.getScaleFactor()));
                            float factor = target / relativeScale;
                            relativeScale = target;
                            transform.postScale(factor, factor,
                                    detector.getFocusX(), detector.getFocusY());
                            setImageMatrix(transform);
                            return true;
                        }
                    });
            gestureDetector = new GestureDetector(PhotoPreviewActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDoubleTap(MotionEvent event) {
                            if (relativeScale > 1.05f) resetImage();
                            else {
                                relativeScale = 2f;
                                transform.postScale(2f, 2f, event.getX(), event.getY());
                                setImageMatrix(transform);
                            }
                            return true;
                        }
                    });
            setOnTouchListener(this);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            resetImage();
        }

        private void resetImage() {
            if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
            float fit = Math.min(getWidth() / (float) bitmap.getWidth(),
                    getHeight() / (float) bitmap.getHeight());
            float dx = (getWidth() - bitmap.getWidth() * fit) / 2f;
            float dy = (getHeight() - bitmap.getHeight() * fit) / 2f;
            transform.reset();
            transform.postScale(fit, fit);
            transform.postTranslate(dx, dy);
            relativeScale = 1f;
            setImageMatrix(transform);
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            gestureDetector.onTouchEvent(event);
            scaleDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && event.getPointerCount() == 1 && !scaleDetector.isInProgress()
                    && relativeScale > 1f) {
                transform.postTranslate(event.getX() - lastX, event.getY() - lastY);
                setImageMatrix(transform);
                lastX = event.getX();
                lastY = event.getY();
            }
            return true;
        }
    }
}
