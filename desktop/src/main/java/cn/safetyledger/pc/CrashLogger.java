package cn.safetyledger.pc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/** Records uncaught desktop exceptions so packaged Windows failures can be diagnosed after the fact. */
public final class CrashLogger {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static volatile Path directory;
    private static volatile boolean installed;
    private CrashLogger() {}

    public static synchronized void install(Path privateDir) {
        directory = privateDir.resolve("logs");
        try { Files.createDirectories(directory); purge(directory, 60); } catch (Exception ignored) {}
        if (installed) return;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record("未捕获异常 · 线程=" + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
        installed = true;
    }

    public static void record(String stage, Throwable error) {
        Path dir = directory;
        if (dir == null || error == null) return;
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("pc-crash-" + LocalDateTime.now().format(TS) + ".log");
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            String text = "安全检查台账 PC 0.2.3 崩溃日志\n"
                    + "时间: " + LocalDateTime.now() + "\n"
                    + "阶段: " + stage + "\n"
                    + "Java: " + System.getProperty("java.version") + "\n"
                    + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                    + "------------------------------------------------------------\n" + sw;
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception ignored) {}
    }

    public static Path latest(Path privateDir) {
        Path dir = privateDir.resolve("logs");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("pc-crash-") && p.getFileName().toString().endsWith(".log"))
                    .max(Comparator.comparingLong(CrashLogger::modified)).orElse(null);
        } catch (Exception ignored) { return null; }
    }

    public static String readTail(Path path, int maxChars) {
        if (path == null || !Files.isRegularFile(path)) return "暂无软件崩溃日志。";
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.length() <= maxChars ? text : "……仅显示日志末尾……\n" + text.substring(text.length() - maxChars);
        } catch (Exception error) { return "读取崩溃日志失败：" + error.getMessage(); }
    }

    private static long modified(Path p){try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception e){return Long.MIN_VALUE;}}
    private static void purge(Path dir,int days){
        long cutoff=System.currentTimeMillis()-days*24L*60L*60L*1000L;
        try(Stream<Path>s=Files.list(dir)){s.filter(Files::isRegularFile).forEach(p->{try{if(p.getFileName().toString().startsWith("pc-crash-")&&Files.getLastModifiedTime(p).toMillis()<cutoff)Files.deleteIfExists(p);}catch(Exception ignored){}});}catch(Exception ignored){}
    }
}
