package cn.safetyledger.app.sync;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small app-private diagnostic log for cloud synchronization.
 * Passwords, tokens and Authorization headers must never be written here.
 */
public final class SyncLog {
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 512L * 1024L;
    private static final long KEEP_BYTES = 384L * 1024L;
    private static final String HEADER = "安全检查台账 云同步诊断日志\n"
            + "说明：日志不记录同步密码、服务器登录密码、Token 或 Authorization。\n\n";

    private SyncLog() {}

    public static void info(Context context, String stage, String message) {
        write(context, "INFO", stage, message, null);
    }

    public static void warn(Context context, String stage, String message) {
        write(context, "WARN", stage, message, null);
    }

    public static void error(Context context, String stage, Throwable error) {
        String message = error == null ? "未知错误" : safe(error.getMessage());
        write(context, "ERROR", stage, message, error);
    }

    public static String read(Context context) {
        synchronized (LOCK) {
            File file = file(context);
            if (!file.isFile()) return HEADER + "暂无同步日志。";
            StringBuilder out = new StringBuilder(HEADER);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            } catch (Exception error) {
                out.append("读取日志失败：").append(safe(error.getMessage()));
            }
            return out.toString();
        }
    }

    public static void export(Context context, OutputStream output) throws Exception {
        if (output == null) throw new java.io.IOException("无法写入日志文件");
        output.write(read(context).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            File file = file(context);
            if (file.isFile()) file.delete();
        }
    }

    private static void write(Context context, String level, String stage,
                              String message, Throwable error) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                File file = file(context);
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                trimIfNeeded(file);
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
                            .format(new Date());
                    String line = time + " [" + level + "] [" + safe(stage) + "] "
                            + safe(message) + "\n";
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    if (error != null) {
                        StringWriter stack = new StringWriter();
                        error.printStackTrace(new PrintWriter(stack));
                        String[] lines = stack.toString().split("\\R");
                        int max = Math.min(lines.length, 24);
                        for (int i = 0; i < max; i++) {
                            output.write(("    " + redact(lines[i]) + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (Exception ignored) {
                // Diagnostic logging must never break synchronization itself.
            }
        }
    }

    private static void trimIfNeeded(File file) throws Exception {
        if (!file.isFile() || file.length() <= MAX_BYTES) return;
        byte[] all;
        try (FileInputStream input = new FileInputStream(file)) {
            all = input.readAllBytes();
        }
        int keep = (int) Math.min(KEEP_BYTES, all.length);
        int start = all.length - keep;
        while (start < all.length && all[start] != '\n') start++;
        if (start < all.length) start++;
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(("--- 较早日志已自动截断 ---\n").getBytes(StandardCharsets.UTF_8));
            if (start < all.length) output.write(all, start, all.length - start);
        }
    }

    private static File file(Context context) {
        return new File(new File(context.getFilesDir(), "sync-diagnostics"), "sync.log");
    }

    private static String safe(String value) {
        return redact(value == null || value.isBlank() ? "-" : value);
    }

    private static String redact(String value) {
        if (value == null) return "";
        String text = value;
        text = text.replaceAll("(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)", "$1<已隐藏>");
        text = text.replaceAll("(?i)(token|password|secret)(\\s*[:=]\\s*)([^\\s,;]+)", "$1$2<已隐藏>");
        return text;
    }
}
