package cn.safetyledger.app;

/** Restrained width model for signatures: natural variation without calligraphy effects. */
final class SignatureStrokeStyle {
    static final float MIN_BODY_WIDTH_DP = 2.0f;
    static final float MAX_BODY_WIDTH_DP = 3.2f;
    static final float BASE_WIDTH_DP = 2.55f;
    static final float START_TIP_WIDTH_DP = 1.35f;
    static final float END_TIP_WIDTH_DP = 1.10f;

    private SignatureStrokeStyle() {}

    static float widthDp(float speedDpPerMs, float turnFactor, float downStrokeFactor,
                         boolean stylus, float pressure) {
        float speed = clamp(speedDpPerMs / 1.6f, 0f, 1f);
        // A fine signing-pen baseline: a pause changes the body by only 0.12dp.
        float width = BASE_WIDTH_DP + 0.12f * (1f - speed) - 0.35f * speed;
        // Direction and corners provide the subtle weight that speed alone cannot create.
        width += 0.28f * clamp(turnFactor, 0f, 1f);
        width += 0.14f * clamp(downStrokeFactor, 0f, 1f);
        if (stylus) {
            // Real stylus pressure contributes no more than ±0.30dp.
            width += (clamp(pressure, 0f, 1f) - 0.5f) * 0.60f;
        }
        return clamp(width, MIN_BODY_WIDTH_DP, MAX_BODY_WIDTH_DP);
    }

    static float smoothWidthDp(float previousWidthDp, float targetWidthDp) {
        return clamp(previousWidthDp * 0.58f + targetWidthDp * 0.42f,
                END_TIP_WIDTH_DP, MAX_BODY_WIDTH_DP);
    }

    static float tailWidthDp(float currentWidthDp) {
        return END_TIP_WIDTH_DP;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
