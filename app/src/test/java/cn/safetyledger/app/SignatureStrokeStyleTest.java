package cn.safetyledger.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SignatureStrokeStyleTest {
    @Test
    public void slowFingerStrokeIsNaturallyHeavierThanFastStroke() {
        float slow = SignatureStrokeStyle.widthDp(0.08f, 0f, 0f, 0f, false, 0f);
        float fast = SignatureStrokeStyle.widthDp(2.2f, 0f, 0f, 0f, false, 0f);
        assertTrue(slow > fast);
        assertTrue(slow / fast > 1.45f);
        assertTrue(slow / fast < 2.2f);
        assertTrue(fast >= SignatureStrokeStyle.MIN_BODY_WIDTH_DP);
    }

    @Test
    public void cornerAndDecelerationAddWeightWithoutCreatingBlob() {
        float plain = SignatureStrokeStyle.widthDp(0.7f, 0f, 0f, 0f, false, 0f);
        float expressive = SignatureStrokeStyle.widthDp(0.7f, 0.8f, 0.7f, 0.8f, false, 0f);
        assertTrue(expressive > plain);
        assertTrue(expressive <= SignatureStrokeStyle.MAX_BODY_WIDTH_DP);
    }

    @Test
    public void stylusPressureStaysSecondaryAndWithinHardLimits() {
        float widest = SignatureStrokeStyle.widthDp(0f, 1f, 1f, 1f, true, 1f);
        float thinnest = SignatureStrokeStyle.widthDp(10f, 0f, 0f, 0f, true, 0f);
        assertTrue(widest <= SignatureStrokeStyle.MAX_BODY_WIDTH_DP);
        assertTrue(thinnest >= SignatureStrokeStyle.MIN_BODY_WIDTH_DP);
    }

    @Test
    public void widthSmoothingThinsFasterThanItThickens() {
        float thinTarget = 1.6f;
        float thickTarget = 3.6f;
        float thinned = SignatureStrokeStyle.smoothWidthDp(3.0f, thinTarget);
        float thickened = SignatureStrokeStyle.smoothWidthDp(2.0f, thickTarget);
        assertTrue((3.0f - thinned) > (thickened - 2.0f));
    }

    @Test
    public void startAndEndRemainTruePenTips() {
        assertTrue(SignatureStrokeStyle.START_TIP_WIDTH_DP < SignatureStrokeStyle.MIN_BODY_WIDTH_DP);
        float tail = SignatureStrokeStyle.tailWidthDp(2.6f);
        assertEquals(SignatureStrokeStyle.END_TIP_WIDTH_DP, tail, 0.001f);
        assertTrue(tail < SignatureStrokeStyle.START_TIP_WIDTH_DP);
    }
}
