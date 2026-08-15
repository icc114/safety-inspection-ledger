package cn.safetyledger.app.sync;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Minimal WebDAV file transport used by Android, NAS and compatible Cloudflare Workers. */
public final class WebDavClient {
    private static final MediaType BINARY = MediaType.parse("application/octet-stream");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final byte[] PROPFIND = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/>"
            + "<d:resourcetype/></d:prop></d:propfind>").getBytes(StandardCharsets.UTF_8);

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build();
    private final String endpoint;
    private final String authorization;
    private final String pairingSpace;

    public WebDavClient(String endpoint, String username, String password, String token) {
        this(endpoint, username, password, token, "", "");
    }

    public WebDavClient(String endpoint, String username, String password, String token,
                        String syncSpace, String syncPassword) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (!value.endsWith("/")) value += "/";
        this.endpoint = value;
        if (token != null && !token.isBlank()) authorization = "Bearer " + token;
        else if (username != null && !username.isBlank()) authorization = "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
        else if (syncSpace != null && !syncSpace.isBlank()
                && syncPassword != null && !syncPassword.isBlank()) {
            authorization = "SafetyLedger " + pairingProof(syncSpace, syncPassword);
        }
        else authorization = "";
        pairingSpace = wireSpace(syncSpace);
    }

    public SyncProvider.ConnectionResult testReadWrite(String space) {
        if (endpoint.isBlank()) return new SyncProvider.ConnectionResult(false, "请填写服务地址");
        try {
            prepare(space);
            String probe = ".safety-ledger-probe-" + UUID.randomUUID() + ".txt";
            byte[] expected = "safety-ledger-webdav-v1".getBytes(StandardCharsets.UTF_8);
            putBytes(fileUrl(space, probe), expected);
            byte[] actual = getBytes(fileUrl(space, probe));
            delete(fileUrl(space, probe));
            if (!java.util.Arrays.equals(expected, actual)) {
                return new SyncProvider.ConnectionResult(false, "服务器读写校验内容不一致");
            }
            return new SyncProvider.ConnectionResult(true,
                    "同步空间建目录、上传、下载和删除校验全部成功");
        } catch (Exception error) {
            return new SyncProvider.ConnectionResult(false, readable(error));
        }
    }

    public void prepare(String space) throws Exception {
        ResponseInfo root = execute("PROPFIND", endpoint, PROPFIND, "0");
        if (!root.successDav()) throw failure("服务地址不是可读的 WebDAV 目录", root);
        mkcol(spaceUrl(space));
        mkcol(devicesUrl(space));
    }

    public List<String> listSnapshots(String space) throws Exception {
        ResponseInfo response = execute("PROPFIND", devicesUrl(space), PROPFIND, "1");
        if (!response.successDav()) throw failure("无法读取同步空间设备列表", response);
        String xmlText = new String(response.body, StandardCharsets.UTF_8);
        String upperXml = xmlText.toUpperCase(java.util.Locale.ROOT);
        if (upperXml.contains("<!DOCTYPE") || upperXml.contains("<!ENTITY")) {
            throw new java.io.IOException("服务器返回了不安全的 XML DTD/ENTITY，已拒绝解析");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        setXmlFeatureSafely(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setXmlFeatureSafely(factory, "http://xml.org/sax/features/external-general-entities", false);
        setXmlFeatureSafely(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        try { factory.setXIncludeAware(false); } catch (RuntimeException | AbstractMethodError ignored) {}
        try { factory.setExpandEntityReferences(false); } catch (RuntimeException | AbstractMethodError ignored) {}
        Document document = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(response.body));
        NodeList hrefs = document.getElementsByTagNameNS("*", "href");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < hrefs.getLength(); i++) {
            String href = hrefs.item(i).getTextContent();
            int slash = href.lastIndexOf('/');
            String name = URLDecoder.decode(slash >= 0 ? href.substring(slash + 1) : href,
                    StandardCharsets.UTF_8.name());
            if (name.endsWith(".safetydata") && !names.contains(name)) names.add(name);
        }
        return names;
    }

    public void download(String space, String name, File target) throws Exception {
        Request request = request(fileUrl(space, name)).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw failure("下载云端快照失败", response);
            ResponseBody body = response.body();
            if (body == null) throw new java.io.IOException("云端返回空文件");
            try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(target)) {
                copy(input, output);
            }
        }
    }

    public void upload(String space, String name, File source) throws Exception {
        RequestBody body = RequestBody.create(BINARY, source);
        try (Response response = http.newCall(request(fileUrl(space, name)).put(body).build()).execute()) {
            if (!response.isSuccessful()) throw failure("上传本机快照失败", response);
        }
    }

    public void deleteSnapshot(String space, String name) throws Exception {
        delete(fileUrl(space, name));
    }

    private void mkcol(String url) throws Exception {
        ResponseInfo response = execute("MKCOL", url, new byte[0], null);
        if (!(response.code == 200 || response.code == 201 || response.code == 204
                || response.code == 301 || response.code == 405)) {
            throw failure("无法创建 WebDAV 同步目录", response);
        }
    }

    private void putBytes(String url, byte[] bytes) throws Exception {
        RequestBody body = RequestBody.create(BINARY, bytes);
        try (Response response = http.newCall(request(url).put(body).build()).execute()) {
            if (!response.isSuccessful()) throw failure("探针文件上传失败", response);
        }
    }

    private byte[] getBytes(String url) throws Exception {
        try (Response response = http.newCall(request(url).get().build()).execute()) {
            if (!response.isSuccessful()) throw failure("探针文件下载失败", response);
            return response.body() == null ? new byte[0] : response.body().bytes();
        }
    }

    private void delete(String url) throws Exception {
        try (Response response = http.newCall(request(url).delete().build()).execute()) {
            if (!(response.isSuccessful() || response.code() == 404)) {
                throw failure("探针文件删除失败", response);
            }
        }
    }

    private ResponseInfo execute(String method, String url, byte[] body, String depth) throws Exception {
        RequestBody requestBody = RequestBody.create(XML, body == null ? new byte[0] : body);
        Request.Builder builder = request(url).method(method, requestBody);
        if (depth != null) builder.header("Depth", depth);
        try (Response response = http.newCall(builder.build()).execute()) {
            byte[] value = response.body() == null ? new byte[0] : response.body().bytes();
            return new ResponseInfo(response.code(), response.message(), value);
        }
    }

    private Request.Builder request(String url) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", "SafetyLedger-Android/2");
        if (!authorization.isBlank()) builder.header("Authorization", authorization);
        if (!pairingSpace.isBlank()) builder.header("X-Safety-Ledger-Space", pairingSpace);
        return builder;
    }

    static String pairingProof(String space, String password) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("safety-ledger-auth-v1\n" + space + "\n" + password)
                            .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * HTTP header values must be ASCII. Keep existing simple ASCII space names unchanged
     * for backward compatibility, and encode names containing Chinese, spaces or other
     * unsafe characters into a deterministic URL-safe namespace used by both the header
     * and the WebDAV path.
     */
    static String wireSpace(String value) {
        String space = value == null ? "" : value.trim();
        if (space.matches("[A-Za-z0-9._-]+")) return space;
        return "u-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(space.getBytes(StandardCharsets.UTF_8));
    }

    private String spaceUrl(String space) { return endpoint + segment(wireSpace(space)) + "/"; }
    private String devicesUrl(String space) { return spaceUrl(space) + "devices/"; }
    private String fileUrl(String space, String name) { return devicesUrl(space) + segment(name); }
    private String segment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Exception failure(String prefix, ResponseInfo response) {
        String text = new String(response.body, StandardCharsets.UTF_8).replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
        if (text.length() > 180) text = text.substring(0, 180);
        return new java.io.IOException(prefix + "：HTTP " + response.code
                + (text.isBlank() ? "" : " · " + text));
    }

    private Exception failure(String prefix, Response response) throws java.io.IOException {
        String text = response.body() == null ? "" : response.body().string();
        if (text.length() > 180) text = text.substring(0, 180);
        return new java.io.IOException(prefix + "：HTTP " + response.code()
                + (text.isBlank() ? "" : " · " + text));
    }

    private static void setXmlFeatureSafely(DocumentBuilderFactory factory,
                                            String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (javax.xml.parsers.ParserConfigurationException
                 | RuntimeException | AbstractMethodError ignored) {
            // Android vendors ship different XML parser implementations. DTD/ENTITY
            // text is rejected before parsing, so unsupported hardening flags must not
            // make otherwise valid WebDAV XML impossible to read.
        }
    }

    private String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static void copy(InputStream input, java.io.OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[65536];
        for (int count; (count = input.read(buffer)) >= 0;) if (count > 0) output.write(buffer, 0, count);
    }

    private record ResponseInfo(int code, String message, byte[] body) {
        boolean successDav() { return (code >= 200 && code < 300) || code == 207; }
    }
}
