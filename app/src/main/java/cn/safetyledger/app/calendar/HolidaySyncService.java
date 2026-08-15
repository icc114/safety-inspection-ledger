package cn.safetyledger.app.calendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import cn.safetyledger.app.data.LedgerRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Refreshes Chinese statutory holiday / adjusted-workday data in the background.
 *
 * The app always reads the local SQLite cache. Network access is only used to refresh
 * a year at most once every 24 hours. If the remote source is unavailable or an annual
 * notice has not been published yet, the existing local cache is kept unchanged.
 */
public final class HolidaySyncService {
    private static final long REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "safety-ledger-holiday-sync");
        t.setDaemon(true);
        return t;
    });
    private static final Set<Integer> RUNNING = new HashSet<>();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    private HolidaySyncService() {}

    public static void refreshAround(Context context, LedgerRepository repo, int year, Runnable callback) {
        refreshYear(context, repo, year - 1, null);
        refreshYear(context, repo, year, callback);
        refreshYear(context, repo, year + 1, null);
    }

    public static void refreshYear(Context context, LedgerRepository repo, int year, Runnable callback) {
        if (year < 2007 || year > 2100) return;
        long last = lastFetch(repo.raw(), year);
        if (System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) {
            if (callback != null) callback.run();
            return;
        }
        synchronized (RUNNING) {
            if (!RUNNING.add(year)) return;
        }
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            boolean changed = false;
            try {
                changed = refresh(repo.raw(), year);
            } catch (Exception ignored) {
                // Offline-first: a failed refresh must never damage the cached calendar.
            } finally {
                synchronized (RUNNING) { RUNNING.remove(year); }
            }
            if (callback != null) {
                android.os.Handler main = new android.os.Handler(app.getMainLooper());
                boolean finalChanged = changed;
                main.post(() -> {
                    if (finalChanged || lastFetch(repo.raw(), year) > 0) callback.run();
                });
            }
        });
    }

    private static boolean refresh(SQLiteDatabase db, int year) throws Exception {
        String[] urls = new String[]{
                "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/" + year + ".json",
                "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/" + year + ".json"
        };
        JSONObject root = null;
        String fetchedFrom = "";
        for (String url : urls) {
            try {
                root = fetchJson(url);
                if (root != null && root.optInt("year") == year && root.optJSONArray("days") != null) {
                    fetchedFrom = url;
                    break;
                }
            } catch (Exception ignored) {
                root = null;
            }
        }
        if (root == null) return false;
        JSONArray days = root.optJSONArray("days");
        if (days == null || days.length() == 0) return false;

        JSONArray papers = root.optJSONArray("papers");
        String officialPaper = papers != null && papers.length() > 0 ? papers.optString(0, fetchedFrom) : fetchedFrom;
        long now = System.currentTimeMillis();

        db.beginTransaction();
        try {
            db.delete("holiday_cache", "date LIKE ?", new String[]{year + "-%"});
            for (int i = 0; i < days.length(); i++) {
                JSONObject day = days.optJSONObject(i);
                if (day == null) continue;
                String date = day.optString("date", "");
                String name = day.optString("name", "节假日");
                if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
                ContentValues values = new ContentValues();
                values.put("date", date);
                values.put("name", name);
                values.put("day_type", day.optBoolean("isOffDay", false) ? "HOLIDAY" : "WORKDAY");
                values.put("source_url", officialPaper);
                values.put("official_release_date", "");
                values.put("fetched_at", now);
                db.insertWithOnConflict("holiday_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            putSetting(db, "holiday_fetch_" + year, Long.toString(now));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    private static JSONObject fetchJson(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "SafetyInspectionLedger/1.2.24")
                .header("Cache-Control", "no-cache")
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return new JSONObject(response.body().string());
        }
    }

    private static long lastFetch(SQLiteDatabase db, int year) {
        String key = "holiday_fetch_" + year;
        try (Cursor cursor = db.query("app_settings", new String[]{"setting_value"},
                "setting_key=?", new String[]{key}, null, null, null)) {
            if (cursor.moveToFirst()) {
                try { return Long.parseLong(cursor.getString(0)); } catch (Exception ignored) {}
            }
        }
        return 0L;
    }

    private static void putSetting(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("setting_key", key);
        values.put("setting_value", value);
        values.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("app_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
