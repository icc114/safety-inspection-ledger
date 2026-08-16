package cn.safetyledger.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Hollow monthly progress ring with a centered percentage label. */
public final class DonutProgressView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int progress;

    public DonutProgressView(Context context) {
        super(context);
        float stroke = Ui.dp(context, 4);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Color.rgb(226, 233, 244));

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(Ui.BLUE);

        textPaint.setColor(Ui.BLUE_DARK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(Ui.dp(context, 13));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setProgress(int value) {
        progress = Math.max(0, Math.min(100, value));
        setContentDescription("完成率 " + progress + "%");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = Ui.dp(getContext(), 6);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.max(0f, Math.min(getWidth(), getHeight()) / 2f - pad);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(arc, 0, 360, false, track);
        if (progress > 0) canvas.drawArc(arc, -90, progress * 3.6f, false, progressPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(progress + "%", cx, baseline, textPaint);
    }
}
