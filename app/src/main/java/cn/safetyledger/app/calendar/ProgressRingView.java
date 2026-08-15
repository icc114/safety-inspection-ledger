package cn.safetyledger.app.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import cn.safetyledger.app.Ui;

/** Compact circular completion-rate indicator used by the dashboard calendar. */
public final class ProgressRingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float fraction;

    public ProgressRingView(Context context) {
        super(context);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Color.rgb(226, 232, 240));
        progress.setStyle(Paint.Style.STROKE);
        progress.setStrokeCap(Paint.Cap.ROUND);
        progress.setColor(Ui.BLUE);
        text.setColor(Ui.TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    public void setFraction(float value) {
        fraction = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float stroke = Ui.dp(getContext(), 6);
        track.setStrokeWidth(stroke);
        progress.setStrokeWidth(stroke);
        float inset = stroke / 2f + Ui.dp(getContext(), 2);
        RectF oval = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(oval, -90, 360, false, track);
        canvas.drawArc(oval, -90, 360f * fraction, false, progress);
        text.setTextSize(Ui.dp(getContext(), 18));
        Paint.FontMetrics fm = text.getFontMetrics();
        float y = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(Math.round(fraction * 100f) + "%", getWidth() / 2f, y, text);
    }
}
