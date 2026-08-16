package cn.safetyledger.pc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Local, offline-first cache of Chinese statutory holidays and makeup workdays. */
public final class PcHolidayCache {
    private static final Gson GSON = new Gson();
    private static final long MAX_AGE = 24L * 60L * 60L * 1000L;
    private static final String SOURCE = "https://timor.tech/api/holiday/year/%d/?type=Y&week=N";
    private static final String OFFICIAL_2026 = "https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm";

    private final Path file;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private Store store;

    public PcHolidayCache(PcConfig config) {
        this.file = config.privateDir().resolve("holiday-cache.json");
        this.store = load();
        seedOfficial2026();
        saveQuietly();
    }

    public synchronized Day day(LocalDate date) {
        return store.days.get(date.toString());
    }

    public synchronized long lastFetched(int year) {
        return store.fetchedAt.getOrDefault(String.valueOf(year), 0L);
    }

    public void syncYearIfStale(int year) {
        if (year < 2020 || year > 2100) return;
        if (System.currentTimeMillis() - lastFetched(year) < MAX_AGE) return;
        syncYear(year);
    }

    public void syncYear(int year) {
        try {
            String url = String.format(Locale.ROOT, SOURCE, year);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "SafetyLedgerPC/0.2.1")
                    .GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) return;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("code") || root.get("code").getAsInt() != 0) return;
            JsonObject holiday = root.getAsJsonObject("holiday");
            if (holiday == null || holiday.size() == 0) return;

            Map<String, Day> incoming = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : holiday.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject item = entry.getValue().getAsJsonObject();
                String date = item.has("date") ? item.get("date").getAsString() : year + "-" + entry.getKey();
                if (!date.startsWith(year + "-")) continue;
                boolean off = !item.has("holiday") || item.get("holiday").getAsBoolean();
                String name = item.has("name") ? item.get("name").getAsString() : (off ? "法定节假日" : "调休上班");
                incoming.put(date, new Day(name, off ? "HOLIDAY" : "WORKDAY", url));
            }
            if (incoming.isEmpty()) return;
            synchronized (this) {
                store.days.entrySet().removeIf(e -> e.getKey().startsWith(year + "-"));
                store.days.putAll(incoming);
                store.fetchedAt.put(String.valueOf(year), System.currentTimeMillis());
                save();
            }
        } catch (Exception ignored) {
            // Network refresh is best-effort. Existing cached data remains available offline.
        }
    }

    private Store load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)) {
                Store loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Store.class);
                if (loaded != null) {
                    if (loaded.days == null) loaded.days = new LinkedHashMap<>();
                    if (loaded.fetchedAt == null) loaded.fetchedAt = new LinkedHashMap<>();
                    return loaded;
                }
            }
        } catch (Exception ignored) {}
        return new Store();
    }

    private synchronized void save() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(store), StandardCharsets.UTF_8);
    }

    private void saveQuietly() { try { save(); } catch (Exception ignored) {} }

    private synchronized void seedOfficial2026() {
        boolean exists = store.days.keySet().stream().anyMatch(k -> k.startsWith("2026-"));
        if (exists) return;
        String[][] ranges = {
                {"2026-01-01","2026-01-03","元旦"},
                {"2026-02-15","2026-02-23","春节"},
                {"2026-04-04","2026-04-06","清明节"},
                {"2026-05-01","2026-05-05","劳动节"},
                {"2026-06-19","2026-06-21","端午节"},
                {"2026-09-25","2026-09-27","中秋节"},
                {"2026-10-01","2026-10-07","国庆节"}
        };
        for (String[] range : ranges) {
            LocalDate a = LocalDate.parse(range[0]);
            LocalDate b = LocalDate.parse(range[1]);
            for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
                store.days.put(d.toString(), new Day(range[2], "HOLIDAY", OFFICIAL_2026));
            }
        }
        String[][] work = {
                {"2026-01-04","元旦调休上班"},
                {"2026-02-14","春节调休上班"},
                {"2026-02-28","春节调休上班"},
                {"2026-05-09","劳动节调休上班"},
                {"2026-09-20","国庆节调休上班"},
                {"2026-10-10","国庆节调休上班"}
        };
        for (String[] row : work) store.days.put(row[0], new Day(row[1], "WORKDAY", OFFICIAL_2026));
    }

    public static final class Store {
        public Map<String, Day> days = new LinkedHashMap<>();
        public Map<String, Long> fetchedAt = new LinkedHashMap<>();
    }

    public static final class Day {
        public String name = "";
        public String type = "";
        public String source = "";
        public Day() {}
        public Day(String name, String type, String source) { this.name = name; this.type = type; this.source = source; }
    }
}
