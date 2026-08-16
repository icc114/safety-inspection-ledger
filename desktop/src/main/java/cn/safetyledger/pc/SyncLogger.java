package cn.safetyledger.pc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/** Small rolling text logger for PC cloud synchronization diagnostics. */
public final class SyncLogger {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final Path directory;
    private final Path file;

    public SyncLogger(Path privateDir) {
        try {
            directory = privateDir.resolve("logs");
            Files.createDirectories(directory);
            purgeOldLogs(directory, 30);
            file = directory.resolve("pc-sync-" + LocalDateTime.now().format(FILE_TS) + ".log");
            Files.writeString(file,
                    "安全检查台账 PC 0.2.3 网络/同步日志\n"
                            + "启动时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n"
                            + "Java: " + System.getProperty("java.version") + "\n"
                            + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                            + "系统代理: " + String.valueOf(java.net.ProxySelector.getDefault()) + "\n"
                            + "------------------------------------------------------------\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception error) {
            throw new IllegalStateException("无法创建同步日志", error);
        }
    }

    public synchronized void log(String message) {
        String line = LocalDateTime.now().format(LINE_TS) + " [" + Thread.currentThread().getName() + "] "
                + safe(message) + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    public synchronized void error(String stage, Throwable error) {
        log("ERROR · " + safe(stage) + " · " + error.getClass().getName() + " · " + safe(error.getMessage()));
        try {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            Files.writeString(file, sw + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    public Path file() { return file; }
    public Path directory() { return directory; }

    public static Path latest(Path privateDir) {
        Path dir = privateDir.resolve("logs");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().startsWith("pc-sync-")
                            && path.getFileName().toString().endsWith(".log"))
                    .max(Comparator.comparingLong(SyncLogger::modifiedTime))
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String readTail(Path path, int maxChars) {
        if (path == null || !Files.isRegularFile(path)) return "暂无同步日志。";
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.length() <= maxChars) return text;
            return "……仅显示日志末尾……\n" + text.substring(text.length() - maxChars);
        } catch (Exception error) {
            return "读取同步日志失败：" + error.getMessage();
        }
    }

    private static void purgeOldLogs(Path dir, int days) {
        try (Stream<Path> stream = Files.list(dir)) {
            long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static long modifiedTime(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception ignored) { return Long.MIN_VALUE; }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
