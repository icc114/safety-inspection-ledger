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
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/** Full-screen local photo preview with pinch and double-tap zoom. */
public final class PhotoPreviewActivity extends Activity {
    private Bitmap bitmap;
    private ZoomImageView imageView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String path = getIntent() == null ? null : getIntent().getStringExtra("photo_path");
            if (path == null || path.isBlank() || !new File(path).isFile()) {
                Ui.toast(this, "照片文件不存在；如果这是云端历史照片，请先完成媒体下载");
                finish();
                return;
            }

            bitmap = decodeForScreen(path);
            if (bitmap == null) {
                Ui.toast(this, "照片文件无法读取或格式不受支持");
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

            imageView = new ZoomImageView();
            imageView.setImageBitmap(bitmap);
            root.addView(imageView, new LinearLayout.LayoutParams(-1, 0, 1));
            setContentView(root);

            // Some Android 11+ vendor builds can return a null insets controller before
            // the decor view is attached. Enter immersive mode only after setContentView.
            root.post(this::hideSystemBarsSafely);
        } catch (Throwable error) {
            Ui.toast(this, "照片预览打开失败：" + readable(error));
            finish();
        }
    }

    private void hideSystemBarsSafely() {
        try {
            Window window = getWindow();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (RuntimeException ignored) {
            // Full-screen mode is cosmetic; preview must still work if a vendor ROM rejects it.
        }
    }

    private Bitmap decodeForScreen(String path) {
        File file = new File(path);
        if (!file.isFile() || file.length() <= 0) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int screenMax = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        // A 2x screen decode is enough for zoom preview and avoids large-camera OOM crashes.
        int target = Math.min(4096, Math.max(1536, screenMax * 2));
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > target && sample < 128) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError first) {
            options.inSampleSize = Math.min(128, Math.max(2, options.inSampleSize * 2));
            try {
                return BitmapFactory.decodeFile(path, options);
            } catch (OutOfMemoryError ignored) {
                return null;
            }
        }
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override
    protected void onDestroy() {
        // Do not recycle the bitmap manually here. Android can still draw the outgoing
        // Activity during the window transition; recycling early can crash with
        // "Canvas: trying to use a recycled bitmap" on some devices.
        if (imageView != null) imageView.setImageDrawable(null);
        imageView = null;
        bitmap = null;
        super.onDestroy();
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
            setClickable(true);
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
                        @Override public boolean onDown(MotionEvent event) {
                            return true;
                        }

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
            Bitmap current = bitmap;
            if (current == null || current.isRecycled() || getWidth() == 0 || getHeight() == 0
                    || current.getWidth() <= 0 || current.getHeight() <= 0) return;
            float fit = Math.min(getWidth() / (float) current.getWidth(),
                    getHeight() / (float) current.getHeight());
            float dx = (getWidth() - current.getWidth() * fit) / 2f;
            float dy = (getHeight() - current.getHeight() * fit) / 2f;
            transform.reset();
            transform.postScale(fit, fit);
            transform.postTranslate(dx, dy);
            relativeScale = 1f;
            setImageMatrix(transform);
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            try {
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
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                }
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
