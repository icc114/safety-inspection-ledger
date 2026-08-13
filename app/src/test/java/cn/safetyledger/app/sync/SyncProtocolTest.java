package cn.safetyledger.app.sync;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncProtocolTest {
    @Test public void envelopeKeepsCrossPlatformIdentityAndRevision() {
        SyncProtocol.ChangeEnvelope x=new SyncProtocol.ChangeEnvelope(1,"inspection","8ebff3ba-f135-48dd-9419-e132a85eb9af",7,"android-a",1234,"UPSERT","abc");
        assertEquals(SyncProtocol.VERSION,x.protocolVersion());
        assertEquals(7,x.revision());
        assertEquals("inspection",x.entityType());
    }
}
