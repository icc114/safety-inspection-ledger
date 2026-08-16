package cn.safetyledger.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** C-shaped overall progress ring whose coloured sections correspond to plan items. */
public final class SegmentedProgressView extends View {
    private static final int[] COLORS = {
            Color.rgb(36, 103, 222),
            Color.rgb(38, 177, 91),
            Color.rgb(243, 156, 45),
            Color.rgb(132, 92, 210)
    };
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segment = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int overallPercent;
    private int[] segmentPercents = new int[0];

    public SegmentedProgressView(Context context) {
        super(context);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(Ui.dp(context, 3.2f));
        track.setColor(Color.rgb(226, 233, 244));
        segment.setStyle(Paint.Style.STROKE);
        segment.setStrokeCap(Paint.Cap.ROUND);
        segment.setStrokeWidth(Ui.dp(context, 3.2f));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Ui.BLUE_DARK);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setOverallPercent(int value) {
        overallPercent = Math.max(0, Math.min(100, value));
        invalidate();
    }

    public void setSegmentPercents(int[] values) {
        if (values == null) {
            segmentPercents = new int[0];
        } else {
            int count = Math.min(4, values.length);
            segmentPercents = new int[count];
            for (int i = 0; i < count; i++) segmentPercents[i] = Math.max(0, Math.min(100, values[i]));
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float pad = Ui.dp(getContext(), 4f);
        float radius = Math.max(0f, Math.min(getWidth(), getHeight()) / 2f - pad);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 280 degrees leaves a clean opening at the right, visually reading as a C.
        final float start = 40f;
        final float totalSweep = 280f;
        canvas.drawArc(arc, start, totalSweep, false, track);

        int count = segmentPercents.length;
        if (count > 0) {
            float slot = totalSweep / count;
            float gap = count == 1 ? 0f : 5f;
            for (int i = 0; i < count; i++) {
                float available = Math.max(0f, slot - gap);
                float sweep = available * segmentPercents[i] / 100f;
                if (sweep <= 0f) continue;
                segment.setColor(COLORS[i % COLORS.length]);
                canvas.drawArc(arc, start + i * slot + gap / 2f, sweep, false, segment);
            }
        }

        String label = overallPercent + "%";
        float inner = Math.max(1f, radius * 2f - segment.getStrokeWidth() - Ui.dp(getContext(), 2));
        float size = Ui.dp(getContext(), 9.2f);
        text.setTextSize(size);
        float width = text.measureText(label);
        float maxWidth = inner * 0.82f;
        if (width > maxWidth && width > 0f) text.setTextSize(Math.max(Ui.dp(getContext(), 6f), size * maxWidth / width));
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, cx, baseline, text);
    }
}
