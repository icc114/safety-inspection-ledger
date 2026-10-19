package cn.safetyledger.app.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cn.safetyledger.app.data.Entities.Media;

import org.junit.Test;

public final class MediaServiceCoordinatesTest {
    @Test public void formatsNorthEastInChinese() {
        assertEquals("北纬 39.90420° · 东经 116.40740°",
                MediaService.chineseCoordinates(39.9042, 116.4074));
    }

    @Test public void formatsSouthWestInChinese() {
        assertEquals("南纬 33.86880° · 西经 151.20930°",
                MediaService.chineseCoordinates(-33.8688, -151.2093));
    }

    @Test public void displaysStoredCoordinatesFromOlderPhoto() {
        Media media = new Media();
        media.latitude = 39.9042;
        media.longitude = 116.4074;
        assertEquals("北纬 39.90420° · 东经 116.40740°",
                MediaService.displayLocation(media));
    }

    @Test public void acceptsValidNorthEastCoordinates() {
        assertTrue(MediaService.isCredibleCoordinates(40.123456, 116.654321));
        assertEquals("北纬 40.12346° · 东经 116.65432°",
                MediaService.chineseCoordinates(40.123456, 116.654321));
    }

    @Test public void rejectsRedactedAndImpossibleCoordinates() {
        assertFalse(MediaService.isCredibleCoordinates(0d, 0d));
        assertFalse(MediaService.isCredibleCoordinates(91d, 116d));
        assertFalse(MediaService.isCredibleCoordinates(40d, 181d));
        assertFalse(MediaService.isCredibleCoordinates(Double.NaN, 116d));
        Media old = new Media();
        old.latitude = 0d;
        old.longitude = 0d;
        old.location = "北纬 0.00000° · 东经 0.00000°";
        assertTrue(MediaService.hasLegacyZeroCoordinates(old));
        assertEquals("", MediaService.displayLocation(old));
    }
}
