package cn.safetyledger.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Finger-first signature surface.
 *
 * The input path is adaptively filtered so slow handwriting is steadier while fast strokes
 * remain responsive. Stroke weight is derived from a short moving speed history, deceleration,
 * direction and corners; real stylus pressure is only a small optional input.
 * The backing bitmap stays transparent and ink bounds are tracked while drawing so saving does
 * not need to scan every pixel of a full-screen signature canvas.
 */
final class NaturalSignatureView extends View {
    private static final float POINT_SPACING_DP = 0.32f;
    private static final float FILTER_ALPHA_SLOW = 0.34f;
    private static final float FILTER_ALPHA_FAST = 0.82f;
    private static final float SPEED_FILTER_ALPHA = 0.34f;
    private static final float FILTER_FULL_SPEED_DP_PER_MS = 2.0f;
    private static final float BOUNDS_SAFETY_DP = 1.5f;

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
    private float filteredX;
    private float filteredY;
    private float lastRawX;
    private float lastRawY;
    private float previousVectorX;
    private float previousVectorY;
    private float currentWidthDp = SignatureStrokeStyle.START_TIP_WIDTH_DP;
    private float smoothedSpeedDpPerMs;
    private float previousSpeedDpPerMs;
    private long lastEventTime;
    private boolean moved;

    private float inkLeft = Float.POSITIVE_INFINITY;
    private float inkTop = Float.POSITIVE_INFINITY;
    private float inkRight = Float.NEGATIVE_INFINITY;
    private float inkBottom = Float.NEGATIVE_INFINITY;

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
        segmentStartX = controlX = filteredX = lastRawX = x;
        segmentStartY = controlY = filteredY = lastRawY = y;
        previousVectorX = previousVectorY = 0f;
        currentWidthDp = SignatureStrokeStyle.START_TIP_WIDTH_DP;
        smoothedSpeedDpPerMs = 0f;
        previousSpeedDpPerMs = 0f;
        lastEventTime = eventTime;
        moved = false;
        empty = false;

        // A very small start point prevents a square/abrupt first pixel without creating a blob.
        paint.setStyle(Paint.Style.FILL);
        inkCanvas.drawCircle(x, y, dp(currentWidthDp) / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        includeInk(x, y, currentWidthDp);
        invalidate();
    }

    private void addPoint(float rawX, float rawY, long eventTime, float pressure, int toolType) {
        float rawVectorX = rawX - lastRawX;
        float rawVectorY = rawY - lastRawY;
        float rawDistance = (float) Math.hypot(rawVectorX, rawVectorY);
        if (rawDistance < dp(POINT_SPACING_DP)) return;

        long elapsed = Math.max(1L, eventTime - lastEventTime);
        float instantSpeed = rawDistance / density / elapsed;
        if (!moved) {
            smoothedSpeedDpPerMs = instantSpeed;
            previousSpeedDpPerMs = instantSpeed;
        } else {
            smoothedSpeedDpPerMs = previousSpeedDpPerMs * (1f - SPEED_FILTER_ALPHA)
                    + instantSpeed * SPEED_FILTER_ALPHA;
        }

        // Adaptive coordinate filtering: more smoothing while writing slowly, less lag on flicks.
        float speedFactor = clamp(smoothedSpeedDpPerMs / FILTER_FULL_SPEED_DP_PER_MS, 0f, 1f);
        float alpha = FILTER_ALPHA_SLOW
                + (FILTER_ALPHA_FAST - FILTER_ALPHA_SLOW) * speedFactor;
        float nextFilteredX = filteredX + (rawX - filteredX) * alpha;
        float nextFilteredY = filteredY + (rawY - filteredY) * alpha;

        float vectorX = nextFilteredX - controlX;
        float vectorY = nextFilteredY - controlY;
        float distance = (float) Math.hypot(vectorX, vectorY);
        lastRawX = rawX;
        lastRawY = rawY;
        lastEventTime = eventTime;
        if (distance < dp(0.08f)) {
            filteredX = nextFilteredX;
            filteredY = nextFilteredY;
            previousSpeedDpPerMs = smoothedSpeedDpPerMs;
            return;
        }

        float turn = turnFactor(previousVectorX, previousVectorY, vectorX, vectorY);
        float deceleration = moved
                ? clamp((previousSpeedDpPerMs - smoothedSpeedDpPerMs) / 0.85f, 0f, 1f)
                : 0f;
        boolean stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_ERASER;
        float downStrokeFactor = Math.max(0f, vectorY) / Math.max(0.001f, distance);
        float targetWidth = SignatureStrokeStyle.widthDp(
                smoothedSpeedDpPerMs, turn, downStrokeFactor, deceleration, stylus, pressure);
        float newWidth = SignatureStrokeStyle.smoothWidthDp(currentWidthDp, targetWidth);

        float endX = (controlX + nextFilteredX) / 2f;
        float endY = (controlY + nextFilteredY) / 2f;
        drawQuadratic(segmentStartX, segmentStartY, controlX, controlY, endX, endY,
                currentWidthDp, newWidth);

        segmentStartX = endX;
        segmentStartY = endY;
        controlX = nextFilteredX;
        controlY = nextFilteredY;
        filteredX = nextFilteredX;
        filteredY = nextFilteredY;
        previousVectorX = vectorX;
        previousVectorY = vectorY;
        currentWidthDp = newWidth;
        previousSpeedDpPerMs = smoothedSpeedDpPerMs;
        moved = true;
        invalidate();
    }

    private void finishStroke(float rawX, float rawY) {
        if (!moved || inkCanvas == null) return;
        // Keep the final point close to the filtered path so ACTION_UP cannot create a last-pixel hook.
        float finalX = filteredX + (rawX - filteredX) * 0.35f;
        float finalY = filteredY + (rawY - filteredY) * 0.35f;
        float tailWidth = SignatureStrokeStyle.tailWidthDp(currentWidthDp);
        drawQuadratic(segmentStartX, segmentStartY, controlX, controlY, finalX, finalY,
                currentWidthDp, tailWidth);
        invalidate();
    }

    private void drawQuadratic(float startX, float startY, float controlPointX,
                               float controlPointY, float endX, float endY,
                               float startWidthDp, float endWidthDp) {
        float length = (float) (Math.hypot(controlPointX - startX, controlPointY - startY)
                + Math.hypot(endX - controlPointX, endY - controlPointY));
        int steps = Math.max(1, (int) Math.ceil(length / dp(0.95f)));
        float previousX = startX;
        float previousY = startY;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float inverse = 1f - t;
            float pointX = inverse * inverse * startX
                    + 2f * inverse * t * controlPointX + t * t * endX;
            float pointY = inverse * inverse * startY
                    + 2f * inverse * t * controlPointY + t * t * endY;
            float widthDp = startWidthDp + (endWidthDp - startWidthDp) * t;
            paint.setStrokeWidth(dp(widthDp));
            inkCanvas.drawLine(previousX, previousY, pointX, pointY, paint);
            includeInk(previousX, previousY, widthDp);
            includeInk(pointX, pointY, widthDp);
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
        resetInkBounds();
        invalidate();
    }

    Bitmap getTransparentSignatureBitmap(boolean trimBlankSpace) {
        if (empty || inkBitmap == null) return null;
        if (!trimBlankSpace) return inkBitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (!hasInkBounds()) return null;

        int left = clampInt((int) Math.floor(inkLeft), 0, inkBitmap.getWidth() - 1);
        int top = clampInt((int) Math.floor(inkTop), 0, inkBitmap.getHeight() - 1);
        int right = clampInt((int) Math.ceil(inkRight), left + 1, inkBitmap.getWidth());
        int bottom = clampInt((int) Math.ceil(inkBottom), top + 1, inkBitmap.getHeight());
        return Bitmap.createBitmap(inkBitmap, left, top, right - left, bottom - top);
    }

    private void includeInk(float x, float y, float widthDp) {
        float radius = dp(widthDp) / 2f + dp(BOUNDS_SAFETY_DP);
        inkLeft = Math.min(inkLeft, x - radius);
        inkTop = Math.min(inkTop, y - radius);
        inkRight = Math.max(inkRight, x + radius);
        inkBottom = Math.max(inkBottom, y + radius);
    }

    private boolean hasInkBounds() {
        return inkLeft <= inkRight && inkTop <= inkBottom;
    }

    private void resetInkBounds() {
        inkLeft = Float.POSITIVE_INFINITY;
        inkTop = Float.POSITIVE_INFINITY;
        inkRight = Float.NEGATIVE_INFINITY;
        inkBottom = Float.NEGATIVE_INFINITY;
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
