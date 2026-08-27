package cn.safetyledger.app;

/**
 * Finger-signature stroke model: visible but restrained weight changes with no calligraphy look.
 */
final class SignatureStrokeStyle {
    static final float MIN_BODY_WIDTH_DP = 1.55f;
    static final float MAX_BODY_WIDTH_DP = 3.75f;
    static final float BASE_WIDTH_DP = 2.35f;
    static final float START_TIP_WIDTH_DP = 1.05f;
    static final float END_TIP_WIDTH_DP = 0.85f;

    private SignatureStrokeStyle() {}

    static float widthDp(float speedDpPerMs, float turnFactor, float downStrokeFactor,
                         boolean stylus, float pressure) {
        return widthDp(speedDpPerMs, turnFactor, downStrokeFactor, 0f, stylus, pressure);
    }

    static float widthDp(float speedDpPerMs, float turnFactor, float downStrokeFactor,
                         float decelerationFactor, boolean stylus, float pressure) {
        float speed = clamp(speedDpPerMs / 2.2f, 0f, 1f);
        float slowWeight = (float) Math.pow(1f - speed, 1.10f);

        // Slow writing gains a little body; fast flicks become visibly finer.
        float width = BASE_WIDTH_DP + 0.78f * slowWeight - 0.58f * speed;
        // Corners and slowing down resemble a natural pen pressing slightly harder.
        width += 0.42f * clamp(turnFactor, 0f, 1f);
        width += 0.16f * clamp(downStrokeFactor, 0f, 1f);
        width += 0.26f * clamp(decelerationFactor, 0f, 1f);

        if (stylus) {
            // Real stylus pressure remains secondary so finger/stylus signatures look consistent.
            width += (clamp(pressure, 0f, 1f) - 0.5f) * 0.56f;
        }
        return clamp(width, MIN_BODY_WIDTH_DP, MAX_BODY_WIDTH_DP);
    }

    static float smoothWidthDp(float previousWidthDp, float targetWidthDp) {
        // Thin out quickly on fast strokes, but thicken more slowly to avoid pause/corner blobs.
        float blend = targetWidthDp < previousWidthDp ? 0.52f : 0.30f;
        if (previousWidthDp < MIN_BODY_WIDTH_DP && targetWidthDp >= MIN_BODY_WIDTH_DP) {
            blend = 0.58f;
        }
        return clamp(previousWidthDp * (1f - blend) + targetWidthDp * blend,
                END_TIP_WIDTH_DP, MAX_BODY_WIDTH_DP);
    }

    static float tailWidthDp(float currentWidthDp) {
        return END_TIP_WIDTH_DP;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
