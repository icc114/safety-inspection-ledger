package cn.safetyledger.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Signature surface with restrained speed, direction and optional stylus-pressure variation.
 * The backing bitmap remains transparent; the view itself is shown on white.
 */
final class NaturalSignatureView extends View {
    private static final float POINT_SPACING_DP = 0.45f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final float density;
    private Bitmap inkBitmap;
    private Canvas inkCanvas;
    private boolean empty = true;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    private float segmentStartX;
    private float segmentStartY;
    private float controlX;
    private float controlY;
    private float previousVectorX;
    private float previousVectorY;
    private float currentWidthDp = SignatureStrokeStyle.BASE_WIDTH_DP;
    private long lastEventTime;
    private boolean moved;

    NaturalSignatureView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        paint.setColor(Color.rgb(15, 23, 42));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setBackgroundColor(Color.WHITE);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;
        Bitmap replacement = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas replacementCanvas = new Canvas(replacement);
        if (inkBitmap != null) {
            replacementCanvas.drawBitmap(inkBitmap, 0f, 0f, null);
            inkBitmap.recycle();
        }
        inkBitmap = replacement;
        inkCanvas = replacementCanvas;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (inkBitmap != null) canvas.drawBitmap(inkBitmap, 0f, 0f, null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                activePointerId = event.getPointerId(0);
                beginStroke(event.getX(0), event.getY(0), event.getEventTime());
                return true;
            case MotionEvent.ACTION_MOVE:
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) return false;
                int toolType = event.getToolType(pointerIndex);
                for (int i = 0; i < event.getHistorySize(); i++) {
                    addPoint(event.getHistoricalX(pointerIndex, i),
                            event.getHistoricalY(pointerIndex, i),
                            event.getHistoricalEventTime(i),
                            event.getHistoricalPressure(pointerIndex, i), toolType);
                }
                addPoint(event.getX(pointerIndex), event.getY(pointerIndex),
                        event.getEventTime(), event.getPressure(pointerIndex), toolType);
                return true;
            case MotionEvent.ACTION_UP:
                int upIndex = event.findPointerIndex(activePointerId);
                if (upIndex >= 0) {
                    finishStroke(event.getX(upIndex), event.getY(upIndex));
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void beginStroke(float x, float y, long eventTime) {
        ensureBitmap();
        segmentStartX = controlX = x;
        segmentStartY = controlY = y;
        previousVectorX = previousVectorY = 0f;
        currentWidthDp = SignatureStrokeStyle.BASE_WIDTH_DP;
        lastEventTime = eventTime;
        moved = false;
        empty = false;

        paint.setStyle(Paint.Style.FILL);
        inkCanvas.drawCircle(x, y, dp(currentWidthDp) / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        invalidate();
    }

    private void addPoint(float x, float y, long eventTime, float pressure, int toolType) {
        float vectorX = x - controlX;
        float vectorY = y - controlY;
        float distance = (float) Math.hypot(vectorX, vectorY);
        if (distance < dp(POINT_SPACING_DP)) return;

        long elapsed = Math.max(1L, eventTime - lastEventTime);
        float speedDpPerMs = distance / density / elapsed;
        float turn = turnFactor(previousVectorX, previousVectorY, vectorX, vectorY);
        boolean stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_ERASER;
        float targetWidth = SignatureStrokeStyle.widthDp(
                speedDpPerMs, turn, stylus, pressure);
        float newWidth = SignatureStrokeStyle.smoothWidthDp(currentWidthDp, targetWidth);

        float endX = (controlX + x) / 2f;
        float endY = (controlY + y) / 2f;
        drawQuadratic(segmentStartX, segmentStartY, controlX, controlY, endX, endY,
                currentWidthDp, newWidth);

        segmentStartX = endX;
        segmentStartY = endY;
        controlX = x;
        controlY = y;
        previousVectorX = vectorX;
        previousVectorY = vectorY;
        currentWidthDp = newWidth;
        lastEventTime = eventTime;
        moved = true;
        invalidate();
    }

    private void finishStroke(float x, float y) {
        if (!moved || inkCanvas == null) return;
        float tailWidth = SignatureStrokeStyle.tailWidthDp(currentWidthDp);
        drawQuadratic(segmentStartX, segmentStartY, controlX, controlY, x, y,
                currentWidthDp, tailWidth);
        invalidate();
    }

    private void drawQuadratic(float startX, float startY, float controlPointX,
                               float controlPointY, float endX, float endY,
                               float startWidthDp, float endWidthDp) {
        float length = (float) (Math.hypot(controlPointX - startX, controlPointY - startY)
                + Math.hypot(endX - controlPointX, endY - controlPointY));
        int steps = Math.max(1, (int) Math.ceil(length / dp(1.25f)));
        float previousX = startX;
        float previousY = startY;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float inverse = 1f - t;
            float pointX = inverse * inverse * startX
                    + 2f * inverse * t * controlPointX + t * t * endX;
            float pointY = inverse * inverse * startY
                    + 2f * inverse * t * controlPointY + t * t * endY;
            paint.setStrokeWidth(dp(startWidthDp + (endWidthDp - startWidthDp) * t));
            inkCanvas.drawLine(previousX, previousY, pointX, pointY, paint);
            previousX = pointX;
            previousY = pointY;
        }
    }

    private float turnFactor(float oldX, float oldY, float newX, float newY) {
        float oldLength = (float) Math.hypot(oldX, oldY);
        float newLength = (float) Math.hypot(newX, newY);
        if (oldLength < 0.001f || newLength < 0.001f) return 0f;
        float cosine = (oldX * newX + oldY * newY) / (oldLength * newLength);
        cosine = Math.max(-1f, Math.min(1f, cosine));
        return (1f - cosine) / 2f;
    }

    boolean isEmpty() {
        return empty;
    }

    void clear() {
        if (inkBitmap != null) inkBitmap.eraseColor(Color.TRANSPARENT);
        empty = true;
        invalidate();
    }

    Bitmap getTransparentSignatureBitmap(boolean trimBlankSpace) {
        if (empty || inkBitmap == null) return null;
        if (!trimBlankSpace) return inkBitmap.copy(Bitmap.Config.ARGB_8888, false);

        int width = inkBitmap.getWidth();
        int height = inkBitmap.getHeight();
        int[] pixels = new int[width * height];
        inkBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if ((pixels[row + x] >>> 24) != 0) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left || bottom < top) return null;
        return Bitmap.createBitmap(inkBitmap, left, top,
                right - left + 1, bottom - top + 1);
    }

    private void ensureBitmap() {
        if (inkBitmap == null && getWidth() > 0 && getHeight() > 0) {
            inkBitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            inkCanvas = new Canvas(inkBitmap);
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
