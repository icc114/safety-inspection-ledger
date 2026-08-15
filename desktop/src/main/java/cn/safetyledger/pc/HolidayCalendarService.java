package cn.safetyledger.pc;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Online-refreshing, locally cached Chinese statutory holiday calendar for the PC client. */
public final class HolidayCalendarService {
    private static final Gson GSON = new Gson();
    private static final long REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);
    private final Path cacheDir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Map<Integer, Map<LocalDate, Day>> memory = new HashMap<>();

    public HolidayCalendarService(Path privateDir) {
        cacheDir = privateDir.resolve("holiday-cache");
    }

    public synchronized Map<LocalDate, Day> year(int year) {
        return memory.computeIfAbsent(year, this::loadLocal);
    }

    public synchronized Day day(LocalDate date) { return year(date.getYear()).get(date); }

    public synchronized boolean refresh(int year) {
        try {
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve(year + ".json");
            if (Files.isRegularFile(file)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < REFRESH_INTERVAL_MS) {
                memory.put(year, loadLocal(year));
                return false;
            }
            String[] urls = {
                    "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/" + year + ".json",
                    "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/" + year + ".json"
            };
            for (String url : urls) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(12))
                            .header("User-Agent", "SafetyInspectionLedgerPC/0.2.1")
                            .header("Cache-Control", "no-cache")
                            .GET().build();
                    HttpResponse<String> response = http.send(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() < 200 || response.statusCode() >= 300) continue;
                    Payload payload = GSON.fromJson(response.body(), Payload.class);
                    if (payload == null || payload.year != year || payload.days == null || payload.days.length == 0) continue;
                    Files.writeString(file, response.body(), StandardCharsets.UTF_8);
                    memory.put(year, parse(payload));
                    return true;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        memory.put(year, loadLocal(year));
        return false;
    }

    private Map<LocalDate, Day> loadLocal(int year) {
        try {
            Path file = cacheDir.resolve(year + ".json");
            if (!Files.isRegularFile(file)) return Collections.emptyMap();
            Payload payload = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Payload.class);
            if (payload == null || payload.days == null) return Collections.emptyMap();
            return parse(payload);
        } catch (Exception ignored) { return Collections.emptyMap(); }
    }

    private static Map<LocalDate, Day> parse(Payload payload) {
        Map<LocalDate, Day> out = new HashMap<>();
        for (Day raw : payload.days) {
            if (raw == null || raw.date == null) continue;
            try { out.put(LocalDate.parse(raw.date), raw); } catch (Exception ignored) {}
        }
        return out;
    }

    private static final class Payload {
        int year;
        String[] papers;
        Day[] days;
    }

    public static final class Day {
        public String name;
        public String date;
        public boolean isOffDay;
    }
}
