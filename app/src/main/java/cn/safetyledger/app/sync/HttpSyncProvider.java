package cn.safetyledger.app.sync;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Connectivity adapter retained for future providers; WebDAV requires a full read/write probe. */
public final class HttpSyncProvider implements SyncProvider {
    private final String providerType;
    public HttpSyncProvider(String type) { providerType = type; }
    public String type() { return providerType; }

    public ConnectionResult test(Map<String, String> config) {
        String endpoint = config.getOrDefault("endpoint", "");
        if (endpoint.isBlank()) return new ConnectionResult(false, "请填写服务地址");
        if ("WebDAV".equals(providerType)) {
            return new WebDavClient(endpoint, config.getOrDefault("username", ""),
                    config.getOrDefault("secret", ""), config.getOrDefault("token", ""))
                    .testReadWrite(config.getOrDefault("space", "safety-ledger"));
        }
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("HEAD");
            String user = config.getOrDefault("username", "");
            if (!user.isBlank()) connection.setRequestProperty("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString((user + ":"
                    + config.getOrDefault("secret", "")).getBytes(StandardCharsets.UTF_8)));
            if (!config.getOrDefault("token", "").isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + config.get("token"));
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) {
                return new ConnectionResult(false, "HTTP " + code
                        + " 仅表示地址可达，尚未验证安全台账同步读写协议");
            }
            return new ConnectionResult(false, "HTTP " + code + "，服务器拒绝连接");
        } catch (Exception error) {
            return new ConnectionResult(false, error.getClass().getSimpleName() + "："
                    + error.getMessage());
        }
    }
}
