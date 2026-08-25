package cn.safetyledger.app.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class RecordUpdateNameTest {
    @Test public void roundTripsUuidRecordName() {
        String device="7185c10d-5e0f-4adb-b990-f53de9302211";
        String inspection="24f2f937-d899-4c71-89f7-8a3f560b0b61";
        CloudSyncService.RecordUpdateName parsed=CloudSyncService.parseRecordUpdateName(
                CloudSyncService.recordUpdateName(device,inspection));
        assertEquals(device,parsed.deviceId());
        assertEquals(inspection,parsed.inspectionId());
    }

    @Test public void rejectsMalformedRecordName() {
        assertNull(CloudSyncService.parseRecordUpdateName("old-device.safetydata"));
    }
}
