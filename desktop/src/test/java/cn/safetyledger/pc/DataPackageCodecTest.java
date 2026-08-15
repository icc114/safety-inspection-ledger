package cn.safetyledger.pc;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import static org.junit.jupiter.api.Assertions.*;

class DataPackageCodecTest {
    @Test void portableV2RoundTripUsesAndroidContainerAndPreservesDatabaseAndMedia() throws Exception {
        Path root=Files.createTempDirectory("safety-codec-test-");
        Path out=Files.createTempFile("safety-portable-",".safetydata");
        try {
            byte[] database="sqlite-placeholder-for-container-test".getBytes(StandardCharsets.UTF_8);
            Files.write(root.resolve("database.sqlite"),database);
            Path media=root.resolve("business_media/inspection-1");Files.createDirectories(media);
            Files.write(media.resolve("photo-1.jpg"),new byte[]{1,2,3,4,5});
            DataPackageCodec.createPortable(root,out);
            byte[] header=Files.readAllBytes(out);
            assertTrue(header.length>"SAFETYLOCAL2".length()+30);
            assertEquals("SAFETYLOCAL2",new String(header,0,"SAFETYLOCAL2".length(),StandardCharsets.US_ASCII));
            assertEquals(2,Byte.toUnsignedInt(header["SAFETYLOCAL2".length()]));
            try(DataPackageCodec.ExtractedPackage extracted=DataPackageCodec.extract(out,null)){
                assertArrayEquals(database,Files.readAllBytes(extracted.database));
                assertArrayEquals(new byte[]{1,2,3,4,5},Files.readAllBytes(extracted.root.resolve("business_media/inspection-1/photo-1.jpg")));
            }
        } finally {
            Files.deleteIfExists(out);
            if(Files.exists(root))try(var paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        }
    }

    @Test void pairingProofAndWireSpaceAreStableAcrossPcAndAndroidProtocol() {
        assertEquals("safety-ledger",CloudClient.wireSpace("safety-ledger"));
        assertTrue(CloudClient.wireSpace("石景山 检查").startsWith("u-"));
        String proof=CloudClient.pairingProof("safety-ledger","12345678");
        assertFalse(proof.isBlank());assertFalse(proof.contains("="));
    }
}
