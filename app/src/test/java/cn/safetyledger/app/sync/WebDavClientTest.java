package cn.safetyledger.app.sync;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class WebDavClientTest {
    private MockWebServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> syncSpace = new AtomicReference<>();

    @Before public void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                authorization.set(request.getHeader("Authorization"));
                syncSpace.set(request.getHeader("X-Safety-Ledger-Space"));
                String path = request.getPath();
                return switch (request.getMethod()) {
                    case "PROPFIND" -> new MockResponse().setResponseCode(207)
                            .setHeader("Content-Type", "application/xml")
                            .setBody("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">"
                                    + "<d:response><d:href>" + path
                                    + "</d:href></d:response></d:multistatus>");
                    case "MKCOL" -> new MockResponse().setResponseCode(201);
                    case "PUT" -> {
                        objects.put(path, request.getBody().readByteArray());
                        yield new MockResponse().setResponseCode(201);
                    }
                    case "GET" -> objects.containsKey(path)
                            ? new MockResponse().setResponseCode(200)
                                    .setBody(new okio.Buffer().write(objects.get(path)))
                            : new MockResponse().setResponseCode(404);
                    case "DELETE" -> {
                        objects.remove(path);
                        yield new MockResponse().setResponseCode(204);
                    }
                    default -> new MockResponse().setResponseCode(405);
                };
            }
        });
        server.start();
    }

    @After public void stop() throws Exception { server.shutdown(); }

    @Test public void connectionRequiresRealCreateUploadDownloadAndDelete() {
        WebDavClient client = new WebDavClient(server.url("/dav/").toString(),
                "worker", "password", "");
        SyncProvider.ConnectionResult result = client.testReadWrite("team-space");
        assertTrue(result.message(), result.success());
        assertTrue("probe must be deleted after verification", objects.isEmpty());
    }

    @Test public void cloudflarePairingUsesDerivedProofWithoutSendingRawPassword() {
        WebDavClient client = new WebDavClient(server.url("/dav/").toString(),
                "", "", "", "team-space", "very-secret-password");
        SyncProvider.ConnectionResult result = client.testReadWrite("team-space");
        assertTrue(result.message(), result.success());
        assertEquals("team-space", syncSpace.get());
        assertEquals("SafetyLedger 9W5bftuNmfoJekwtzSrqWV1eAKDdGD5_HbMhtGrzj-k",
                authorization.get());
        assertTrue(!authorization.get().contains("very-secret-password"));
    }

    @Test public void chineseSpaceNameIsEncodedToAsciiHeaderAndPathNamespace() {
        WebDavClient client = new WebDavClient(server.url("/dav/").toString(),
                "", "", "", "2026安全检查", "Sjs123456");
        SyncProvider.ConnectionResult result = client.testReadWrite("2026安全检查");
        assertTrue(result.message(), result.success());
        assertEquals("u-MjAyNuWuieWFqOajgOafpQ", syncSpace.get());
        assertTrue("HTTP header must not contain raw Chinese", !syncSpace.get().contains("安全检查"));
        assertTrue("WebDAV path must use the same safe namespace",
                objects.keySet().stream().noneMatch(path -> path.contains("安全检查")));
    }
}
