package cn.safetyledger.app;

/** Restrained width model for signatures: natural variation without calligraphy effects. */
final class SignatureStrokeStyle {
    static final float MIN_WIDTH_DP = 3.8f;
    static final float MAX_WIDTH_DP = 5.2f;
    static final float BASE_WIDTH_DP = 4.45f;

    private SignatureStrokeStyle() {}

    static float widthDp(float speedDpPerMs, float turnFactor,
                         boolean stylus, float pressure) {
        float speed = clamp(speedDpPerMs / 1.4f, 0f, 1f);
        // Ordinary writing stays close to the baseline. A pause only adds 0.18dp,
        // so it cannot create the heavy blobs produced by a pure velocity model.
        float width = BASE_WIDTH_DP + 0.18f * (1f - speed) - 0.55f * speed;
        // Corners receive a very small amount of weight, like a real change of direction.
        width += 0.22f * clamp(turnFactor, 0f, 1f);
        if (stylus) {
            // Real stylus pressure is useful, but deliberately contributes no more than ±0.35dp.
            width += (clamp(pressure, 0f, 1f) - 0.5f) * 0.70f;
        }
        return clamp(width, MIN_WIDTH_DP, MAX_WIDTH_DP);
    }

    static float smoothWidthDp(float previousWidthDp, float targetWidthDp) {
        return clamp(previousWidthDp * 0.72f + targetWidthDp * 0.28f,
                MIN_WIDTH_DP, MAX_WIDTH_DP);
    }

    static float tailWidthDp(float currentWidthDp) {
        return clamp(currentWidthDp * 0.84f, MIN_WIDTH_DP, MAX_WIDTH_DP);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
