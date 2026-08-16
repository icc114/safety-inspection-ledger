package cn.safetyledger.pc;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebDAV-compatible transport matching the Android Cloudflare Worker protocol. */
public final class CloudClient {
    private static final String PROPFIND = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>";
    private static final Pattern HREF = Pattern.compile("(?is)<(?:[A-Za-z0-9_-]+:)?href[^>]*>(.*?)</(?:[A-Za-z0-9_-]+:)?href>");
    private static final Set<Integer> RETRY_CODES = Set.of(408, 425, 429, 500, 502, 503, 504);
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String endpoint;
    private final String rawSpace;
    private final String wireSpace;
    private final String authorization;
    private final SyncLogger logger;

    public CloudClient(String endpoint, String space, char[] password) {
        this(endpoint, space, password, null);
    }

    public CloudClient(String endpoint, String space, char[] password, SyncLogger logger) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (!value.endsWith("/")) value += "/";
        this.endpoint = value;
        this.rawSpace = space == null ? "" : space.trim();
        this.wireSpace = wireSpace(this.rawSpace);
        this.authorization = "SafetyLedger " + pairingProof(this.rawSpace, new String(password));
        this.logger = logger;
    }

    public void testReadWrite() throws Exception {
        log("开始云端读写测试");
        prepare();
        String name = ".safety-pc-probe-" + UUID.randomUUID() + ".txt";
        URI url = fileUrl(name);
        byte[] expected = "safety-ledger-pc-v2".getBytes(StandardCharsets.UTF_8);
        sendBytes("PUT", url, expected, null, 200, 201, 204);
        HttpResponse<byte[]> got = send("GET", url, HttpRequest.BodyPublishers.noBody(), null, HttpResponse.BodyHandlers.ofByteArray());
        if (got.statusCode() / 100 != 2 || !Arrays.equals(expected, got.body())) {
            throw failure("读写校验失败", got.statusCode(), got.body());
        }
        sendBytes("DELETE", url, new byte[0], null, 200, 202, 204, 404);
        log("云端读写测试通过");
    }

    public void prepare() throws Exception {
        log("检查 WebDAV 根目录");
        HttpResponse<byte[]> root = send("PROPFIND", URI.create(endpoint), HttpRequest.BodyPublishers.ofString(PROPFIND), "0", HttpResponse.BodyHandlers.ofByteArray());
        if (!davOk(root.statusCode())) throw failure("服务地址不是可读的 WebDAV 目录", root.statusCode(), root.body());
        mkcol(spaceUrl());
        mkcol(devicesUrl());
        mkcol(deviceControlUrl());
        log("同步目录准备完成");
    }

    public List<String> listSnapshots() throws Exception {
        log("读取设备快照列表");
        HttpResponse<byte[]> response = send("PROPFIND", devicesUrl(), HttpRequest.BodyPublishers.ofString(PROPFIND), "1", HttpResponse.BodyHandlers.ofByteArray());
        if (!davOk(response.statusCode())) throw failure("无法读取检查内容同步目录", response.statusCode(), response.body());
        String xml = new String(response.body(), StandardCharsets.UTF_8);
        String upper = xml.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new IOException("服务器返回了不安全的 XML");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher matcher = HREF.matcher(xml);
        while (matcher.find()) {
            String href = unescapeXml(matcher.group(1).trim());
            int slash = href.lastIndexOf('/');
            String encoded = slash >= 0 ? href.substring(slash + 1) : href;
            if (encoded.isBlank()) continue;
            String name = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            if (name.endsWith(".safetydata")) names.add(name);
        }
        log("读取到 " + names.size() + " 个设备快照");
        return new ArrayList<>(names);
    }

    public String fingerprint(String name) throws Exception {
        HttpResponse<Void> response = send("HEAD", fileUrl(name), HttpRequest.BodyPublishers.noBody(), null, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) throw new IOException("读取云端快照状态失败：HTTP " + response.statusCode());
        String etag = response.headers().firstValue("etag").orElse("");
        String len = response.headers().firstValue("content-length").orElse("");
        String modified = response.headers().firstValue("last-modified").orElse("");
        String value = etag + "|" + len + "|" + modified;
        log("快照状态 " + name + " · size=" + (len.isBlank() ? "?" : len));
        return value;
    }

    public void download(String name, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Files.deleteIfExists(temp);
            long started = System.nanoTime();
            try {
                HttpRequest request = request(fileUrl(name)).GET().build();
                log("HTTP GET " + safeUri(fileUrl(name)) + " · 下载尝试 " + attempt + "/" + MAX_ATTEMPTS);
                HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temp,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
                long ms = (System.nanoTime() - started) / 1_000_000L;
                log("HTTP GET -> " + response.statusCode() + " · " + ms + "ms");
                if (response.statusCode() / 100 == 2) {
                    try {
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception atomicUnsupported) {
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    log("下载完成 " + name + " · " + Files.size(target) + " bytes");
                    return;
                }
                IOException statusError = new IOException("下载云端检查内容失败：HTTP " + response.statusCode());
                last = statusError;
                if (!retryable(response.statusCode()) || attempt == MAX_ATTEMPTS) throw statusError;
            } catch (HttpTimeoutException | IOException error) {
                last = error;
                log("下载失败（第 " + attempt + " 次）：" + error.getClass().getSimpleName() + " · " + String.valueOf(error.getMessage()));
                if (attempt == MAX_ATTEMPTS) throw error;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            sleepBackoff(attempt);
        }
        if (last instanceof Exception) throw last;
    }

    public boolean isDeviceLoggedOut(String deviceId) throws Exception {
        HttpResponse<byte[]> response = send("GET", controlFileUrl(deviceId + ".logout"), HttpRequest.BodyPublishers.noBody(), null, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return false;
        if (response.statusCode() / 100 == 2) return true;
        throw failure("无法读取电脑设备登出状态", response.statusCode(), response.body());
    }

    public void registerPcDevice(String deviceId, String displayName) throws Exception {
        String role = existingDeviceRole(deviceId);
        long now = System.currentTimeMillis();
        String json = "{\"version\":1,\"deviceId\":\"" + escapeJson(deviceId) + "\",\"displayName\":\"" + escapeJson(displayName) + "\",\"role\":\"" + role + "\",\"lastSeenAt\":" + now + ",\"updatedAt\":" + now + ",\"platform\":\"WINDOWS\"}";
        sendBytes("PUT", controlFileUrl(deviceId + ".device.json"), json.getBytes(StandardCharsets.UTF_8), null, 200, 201, 204);
        log("电脑设备登记完成 · role=" + role);
    }

    private String existingDeviceRole(String deviceId) throws Exception {
        HttpResponse<byte[]> response = send("GET", controlFileUrl(deviceId + ".device.json"), HttpRequest.BodyPublishers.noBody(), null, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return "FIELD";
        if (response.statusCode() / 100 != 2) throw failure("无法读取电脑设备信息", response.statusCode(), response.body());
        String json = new String(response.body(), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\"role\"\s*:\s*\"(OWNER|ADMIN|FIELD|LOGGED_OUT)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "FIELD";
    }

    private void mkcol(URI uri) throws Exception {
        HttpResponse<byte[]> response = send("MKCOL", uri, HttpRequest.BodyPublishers.noBody(), null, HttpResponse.BodyHandlers.ofByteArray());
        int c = response.statusCode();
        if (!(c == 200 || c == 201 || c == 204 || c == 301 || c == 405)) throw failure("无法创建同步目录", c, response.body());
    }

    private void sendBytes(String method, URI uri, byte[] body, String depth, int... accepted) throws Exception {
        HttpResponse<byte[]> response = send(method, uri,
                body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body),
                depth, HttpResponse.BodyHandlers.ofByteArray());
        for (int code : accepted) if (response.statusCode() == code) return;
        throw failure("云端请求失败", response.statusCode(), response.body());
    }

    private <T> HttpResponse<T> send(String method, URI uri, HttpRequest.BodyPublisher body, String depth,
                                     HttpResponse.BodyHandler<T> handler) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest.Builder builder = request(uri).method(method, body);
            if (depth != null) builder.header("Depth", depth);
            long started = System.nanoTime();
            try {
                log("HTTP " + method + " " + safeUri(uri) + " · 尝试 " + attempt + "/" + MAX_ATTEMPTS);
                HttpResponse<T> response = http.send(builder.build(), handler);
                long ms = (System.nanoTime() - started) / 1_000_000L;
                log("HTTP " + method + " -> " + response.statusCode() + " · " + ms + "ms");
                if (retryable(response.statusCode()) && attempt < MAX_ATTEMPTS) {
                    log("服务端暂时不可用，准备自动重试");
                    sleepBackoff(attempt);
                    continue;
                }
                return response;
            } catch (HttpTimeoutException | IOException error) {
                last = error;
                log("网络异常（第 " + attempt + " 次）：" + error.getClass().getSimpleName() + " · " + String.valueOf(error.getMessage()));
                if (attempt == MAX_ATTEMPTS) throw error;
                sleepBackoff(attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
        throw last == null ? new IOException("云端请求失败") : last;
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "SafetyLedger-PC/0.2.2")
                .header("Authorization", authorization)
                .header("X-Safety-Ledger-Space", wireSpace);
    }

    private void log(String text) {
        if (logger != null) logger.log(text);
    }

    private static boolean retryable(int code) { return RETRY_CODES.contains(code); }

    private static void sleepBackoff(int attempt) throws InterruptedException {
        long delay = switch (attempt) { case 1 -> 800L; case 2 -> 1800L; default -> 3500L; };
        Thread.sleep(delay);
    }

    private static String safeUri(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return host + path;
    }

    private URI spaceUrl() { return URI.create(endpoint + segment(wireSpace) + "/"); }
    private URI devicesUrl() { return URI.create(spaceUrl() + "devices/"); }
    private URI deviceControlUrl() { return URI.create(spaceUrl() + "device-control/"); }
    private URI fileUrl(String name) { return URI.create(devicesUrl() + segment(name)); }
    private URI controlFileUrl(String name) { return URI.create(deviceControlUrl() + segment(name)); }
    private static String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static boolean davOk(int code) { return code == 200 || code == 207; }

    private static IOException failure(String prefix, int code, byte[] body) {
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8)
                .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (text.length() > 220) text = text.substring(0, 220);
        return new IOException(prefix + "：HTTP " + code + (text.isBlank() ? "" : " · " + text));
    }

    private static String unescapeXml(String value) {
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public static String pairingProof(String space, String password) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("safety-ledger-auth-v1\n" + space + "\n" + password).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String wireSpace(String value) {
        String s = value == null ? "" : value.trim();
        if (s.matches("[A-Za-z0-9._-]+")) return s;
        return "u-" + Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
