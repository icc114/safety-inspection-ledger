package cn.safetyledger.app.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.location.Location;
import android.media.ExifInterface;
import android.net.Uri;

import cn.safetyledger.app.data.Entities.Media;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Keeps the untouched source and creates a business JPEG from available EXIF metadata. */
public final class MediaService {
    private static final DateTimeFormatter EXIF_TIME =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US);
    private static final DateTimeFormatter WATERMARK_TIME =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA);
    private final Context context;

    public MediaService(Context context) {
        this.context = context.getApplicationContext();
    }

    public File mediaDir(String inspectionId) {
        File directory = new File(context.getFilesDir(), "business_media/" + inspectionId);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建媒体目录");
        }
        return directory;
    }

    public Media importAndWatermark(Uri source, String inspectionId, String itemId,
                                    String category, String inspectionPlace,
                                    Location capturedLocation, boolean capturedNow)
            throws IOException {
        PhotoMetadata metadata = readMetadata(source);
        Long capturedAt = metadata.capturedAt;
        if (capturedAt == null && capturedNow) capturedAt = System.currentTimeMillis();
        Double latitude = metadata.latitude;
        Double longitude = metadata.longitude;
        if (latitude == null && capturedNow && capturedLocation != null) {
            latitude = capturedLocation.getLatitude();
            longitude = capturedLocation.getLongitude();
        }

        String id = UUID.randomUUID().toString();
        File directory = mediaDir(inspectionId);
        File originalFile = new File(directory, id + "-original.bin");
        copySource(source, originalFile);

        Bitmap decoded;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            decoded = BitmapFactory.decodeStream(input);
        }
        if (decoded == null) throw new IOException("无法读取照片");
        Bitmap oriented = orient(decoded, metadata.orientation);
        if (oriented != decoded) decoded.recycle();
        int maximum = 2400;
        float scale = Math.min(1f, maximum
                / (float) Math.max(oriented.getWidth(), oriented.getHeight()));
        Bitmap resized = scale < 1f
                ? Bitmap.createScaledBitmap(oriented, Math.round(oriented.getWidth() * scale),
                        Math.round(oriented.getHeight() * scale), true)
                : oriented;
        if (resized != oriented) oriented.recycle();
        Bitmap business = resized.copy(Bitmap.Config.ARGB_8888, true);
        resized.recycle();

        List<String> lines = new ArrayList<>();
        if (capturedAt != null) {
            lines.add("拍摄时间：" + Instant.ofEpochMilli(capturedAt)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime().format(WATERMARK_TIME));
        }
        String gpsText = "";
        if (latitude != null && longitude != null) {
            gpsText = chineseCoordinates(latitude, longitude);
            lines.add("拍摄位置：" + gpsText);
        }
        // Do not invent a watermark from the inspection form. If EXIF/fresh-camera
        // metadata is unavailable, the business JPEG remains visually unchanged.
        if (!lines.isEmpty()) drawWatermark(business, lines);

        File output = new File(directory, id + ".jpg");
        try (OutputStream stream = new FileOutputStream(output)) {
            if (!business.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                throw new IOException("照片压缩失败");
            }
        } finally {
            business.recycle();
        }

        Media media = new Media();
        media.id = id;
        media.inspectionId = inspectionId;
        media.itemId = itemId;
        media.category = category;
        media.localPath = output.getAbsolutePath();
        media.capturedAt = capturedAt == null ? System.currentTimeMillis() : capturedAt;
        media.location = gpsText;
        media.latitude = latitude;
        media.longitude = longitude;
        media.sha256 = sha256(output);
        media.mime = "image/jpeg";
        media.size = output.length();
        return media;
    }

    private void copySource(Uri source, File target) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("原始照片读取失败");
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) output.write(buffer, 0, count);
            }
        }
    }

    private PhotoMetadata readMetadata(Uri source) {
        PhotoMetadata metadata = new PhotoMetadata();
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) return metadata;
            ExifInterface exif = new ExifInterface(input);
            metadata.orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            String time = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (time == null) time = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED);
            if (time == null) time = exif.getAttribute(ExifInterface.TAG_DATETIME);
            metadata.capturedAt = parseTime(time);
            float[] coordinates = new float[2];
            if (exif.getLatLong(coordinates)) {
                metadata.latitude = (double) coordinates[0];
                metadata.longitude = (double) coordinates[1];
            }
        } catch (Exception ignored) {
            // Original photo remains importable even when its EXIF block is absent or malformed.
        }
        return metadata;
    }

    private Long parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value.trim(), EXIF_TIME)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Bitmap orient(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) matrix.postRotate(90);
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) matrix.postRotate(180);
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) matrix.postRotate(270);
        else return source;
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(),
                matrix, true);
    }

    private String chineseCoordinates(double latitude, double longitude) {
        return String.format(Locale.CHINA, "%s %.6f°，%s %.6f°",
                latitude >= 0 ? "北纬" : "南纬", Math.abs(latitude),
                longitude >= 0 ? "东经" : "西经", Math.abs(longitude));
    }

    private void drawWatermark(Bitmap bitmap, List<String> lines) {
        Canvas canvas = new Canvas(bitmap);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(Math.max(28, bitmap.getWidth() / 35f));
        text.setColor(Color.WHITE);
        text.setShadowLayer(3, 0, 1, Color.BLACK);
        float padding = Math.max(20, bitmap.getWidth() / 80f);
        float lineHeight = text.getTextSize() * 1.4f;
        Paint background = new Paint();
        background.setColor(0x99000000);
        canvas.drawRect(0, bitmap.getHeight() - padding * 2 - lineHeight * lines.size(),
                bitmap.getWidth(), bitmap.getHeight(), background);
        float y = bitmap.getHeight() - padding - lineHeight * (lines.size() - 1);
        for (String line : lines) {
            canvas.drawText(line, padding, y, text);
            y += lineHeight;
        }
    }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) > 0;) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder value = new StringBuilder();
            for (byte part : digest.digest()) value.append(String.format("%02x", part));
            return value.toString();
        } catch (Exception error) {
            throw new IOException(error);
        }
    }

    public void deleteInspectionMedia(String inspectionId) {
        File directory = mediaDir(inspectionId);
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) if (!file.delete()) file.deleteOnExit();
        }
        directory.delete();
    }

    private static final class PhotoMetadata {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        Long capturedAt;
        Double latitude;
        Double longitude;
    }
}
