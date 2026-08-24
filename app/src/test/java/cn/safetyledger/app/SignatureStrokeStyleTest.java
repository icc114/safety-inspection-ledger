package cn.safetyledger.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SignatureStrokeStyleTest {
    @Test
    public void pauseDoesNotCreateHeavyBlob() {
        float paused = SignatureStrokeStyle.widthDp(0f, 0f, false, 0f);
        assertEquals(4.63f, paused, 0.001f);
        assertTrue(paused < 4.7f);
    }

    @Test
    public void ordinarySpeedVariationRemainsRestrained() {
        float slow = SignatureStrokeStyle.widthDp(0.1f, 0f, false, 0f);
        float fast = SignatureStrokeStyle.widthDp(2.0f, 0f, false, 0f);
        assertTrue(slow > fast);
        assertTrue(slow / fast < 1.25f);
        assertTrue(fast >= SignatureStrokeStyle.MIN_WIDTH_DP);
    }

    @Test
    public void cornerAndStylusPressureStayWithinHardLimits() {
        float widest = SignatureStrokeStyle.widthDp(0f, 1f, true, 1f);
        float thinnest = SignatureStrokeStyle.widthDp(10f, 0f, true, 0f);
        assertTrue(widest <= SignatureStrokeStyle.MAX_WIDTH_DP);
        assertTrue(thinnest >= SignatureStrokeStyle.MIN_WIDTH_DP);
    }

    @Test
    public void endingTapersOnlySlightly() {
        float tail = SignatureStrokeStyle.tailWidthDp(4.6f);
        assertEquals(SignatureStrokeStyle.MIN_WIDTH_DP, tail, 0.1f);
        assertTrue(tail < 4.6f);
    }
}
