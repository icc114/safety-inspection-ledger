package cn.safetyledger.app.sync;

import org.junit.Test;

import java.net.SocketTimeoutException;

import static org.junit.Assert.*;

public class SyncErrorFormatterTest {
    @Test public void timeoutIsReportedAsNetworkProblem() {
        SocketTimeoutException timeout = new SocketTimeoutException(
                "failed to connect to safety-inspection-ledger-cloud.icc2820.workers.dev after 12000ms");
        assertTrue(SyncErrorFormatter.isNetwork(timeout));
        String message = SyncErrorFormatter.format(timeout);
        assertTrue(message.startsWith("网络连接问题："));
        assertTrue(message.contains("本机检查记录不会因此丢失"));
        assertEquals("安全检查台账同步失败 · 网络问题",
                SyncErrorFormatter.notificationTitle(timeout));
    }
}
