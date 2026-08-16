package cn.safetyledger.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Hollow progress ring used by each monthly plan item. */
public final class DonutProgressView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int progress;
    private int insetDp = 5;
    private boolean compact;
    private String centerText;

    public DonutProgressView(Context context) {
        super(context);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Color.rgb(226, 233, 244));
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(Ui.BLUE);
        textPaint.setColor(Ui.BLUE_DARK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setCompact(false);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setCompact(boolean compact) {
        this.compact = compact;
        float stroke = Ui.dp(getContext(), compact ? 3 : 4);
        track.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);
        insetDp = compact ? 3 : 5;
        invalidate();
    }

    public void setCenterText(String value) {
        centerText = value;
        invalidate();
    }

    public void setProgress(int value) {
        progress = Math.max(0, Math.min(100, value));
        setContentDescription("完成率 " + progress + "%");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = Ui.dp(getContext(), insetDp);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.max(0f, Math.min(getWidth(), getHeight()) / 2f - pad);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(arc, 0, 360, false, track);
        if (progress > 0) canvas.drawArc(arc, -90, progress * 3.6f, false, progressPaint);

        String label = centerText == null ? progress + "%" : centerText;
        float inner = Math.max(1f, radius * 2f - progressPaint.getStrokeWidth() - Ui.dp(getContext(), 2));
        float maxWidth = inner * 0.88f;
        float size = Ui.dp(getContext(), compact ? 7 : 12);
        textPaint.setTextSize(size);
        float width = textPaint.measureText(label);
        if (width > maxWidth && width > 0f) {
            textPaint.setTextSize(Math.max(Ui.dp(getContext(), 5), size * maxWidth / width));
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, cx, baseline, textPaint);
    }
}
