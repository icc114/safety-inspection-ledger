package cn.safetyledger.app.holiday;

import android.content.Context;

import cn.safetyledger.app.data.LedgerRepository;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads Chinese statutory holiday / makeup-workday data when the device has network access.
 * The UI always reads the local SQLite cache, so offline use is unaffected.
 */
public final class HolidaySyncService {
    public static final String SOURCE_TEMPLATE = "https://timor.tech/api/holiday/year/%d/?type=Y&week=N";
    private static final long MAX_AGE = 24L * 60L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "safety-ledger-holiday-sync");
        thread.setDaemon(true);
        return thread;
    });
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().build();

    private HolidaySyncService() {}

    public static void syncYearAsync(Context context, int year, Runnable completion) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try { syncYear(app, year, false); } catch (Exception ignored) {}
            if (completion != null) completion.run();
        });
    }

    public static boolean syncYear(Context context, int year, boolean force) throws Exception {
        if (year < 2020 || year > 2100) return false;
        LedgerRepository repo = new LedgerRepository(context);
        if (!force && System.currentTimeMillis() - repo.holidayLastFetchedAt(year) < MAX_AGE) return false;

        String url = String.format(java.util.Locale.ROOT, SOURCE_TEMPLATE, year);
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "SafetyLedger/1.2.24")
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("节假日数据请求失败：HTTP " + response.code());
            }
            JSONObject root = new JSONObject(response.body().string());
            if (root.optInt("code", -1) != 0) throw new IOException("节假日数据源返回异常");
            JSONObject holiday = root.optJSONObject("holiday");
            if (holiday == null || holiday.length() == 0) return false;

            Map<String, String[]> rows = new LinkedHashMap<>();
            Iterator<String> keys = holiday.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = holiday.optJSONObject(key);
                if (item == null) continue;
                String date = item.optString("date", "");
                if (date.isBlank()) date = year + "-" + key;
                if (!date.startsWith(year + "-")) continue;
                boolean off = item.optBoolean("holiday", true);
                String name = item.optString("name", off ? "法定节假日" : "调休上班");
                rows.put(date, new String[]{name, off ? "HOLIDAY" : "WORKDAY"});
            }
            if (rows.isEmpty()) return false;
            repo.replaceHolidayYear(year, rows, url);
            return true;
        }
    }
}
