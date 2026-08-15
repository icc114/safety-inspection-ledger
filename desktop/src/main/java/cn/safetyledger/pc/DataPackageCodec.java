package cn.safetyledger.pc;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.*;

/** Reads both Android cloud snapshots and phone-exported portable .safetydata files. */
public final class DataPackageCodec {
    private static final byte[] CLOUD_MAGIC = "SAFETYDATA".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PORTABLE_MAGIC = "SAFETYLOCAL2".getBytes(StandardCharsets.US_ASCII);
    private static final char[] PORTABLE_KEY = "safety-ledger-portable-backup-v2".toCharArray();
    private static final int ITERATIONS = 310_000;
    private static final int CHUNK_SIZE = 256 * 1024;
    private static final int TAG_BYTES = 16;

    private DataPackageCodec() {}

    public static ExtractedPackage extract(Path source, char[] cloudPassword) throws Exception {
        if (!Files.isRegularFile(source)) throw new IOException("找不到数据包：" + source);
        Path zip = Files.createTempFile("safety-pc-", ".zip");
        Path root = Files.createTempDirectory("safety-pc-extract-");
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            input.mark(32);
            byte[] prefix = input.readNBytes(Math.max(CLOUD_MAGIC.length, PORTABLE_MAGIC.length));
            input.reset();
            byte[] magic;
            char[] password;
            if (startsWith(prefix, PORTABLE_MAGIC)) {
                magic = PORTABLE_MAGIC;
                password = PORTABLE_KEY.clone();
            } else if (startsWith(prefix, CLOUD_MAGIC)) {
                if (cloudPassword == null || cloudPassword.length < 8) throw new SecurityException("云端数据包需要同步空间密码");
                magic = CLOUD_MAGIC;
                password = cloudPassword.clone();
            } else {
                throw new IOException("不是安全检查台账 .safetydata 数据包");
            }
            decrypt(input, zip, password, magic);
            verifyAndUnzip(zip, root);
            return new ExtractedPackage(root, root.resolve("database.sqlite"));
        } catch (Exception error) {
            deleteTree(root);
            throw error;
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    public static void createPortable(Path extractedRoot, Path destination) throws Exception {
        Path db = extractedRoot.resolve("database.sqlite");
        if (!Files.isRegularFile(db)) throw new IOException("没有可导出的 database.sqlite");
        Path zip = Files.createTempFile("safety-pc-portable-", ".zip");
        try {
            Properties manifest = new Properties();
            Path oldManifest = extractedRoot.resolve("manifest.properties");
            if (Files.isRegularFile(oldManifest)) try (InputStream in = Files.newInputStream(oldManifest)) { manifest.load(in); }
            manifest.setProperty("format", "safetydata");
            manifest.setProperty("formatVersion", "1");
            manifest.setProperty("container", "safety-ledger-portable");
            manifest.setProperty("portableCompatibility", "android-windows");
            manifest.setProperty("createdAt", String.valueOf(System.currentTimeMillis()));
            manifest.setProperty("databaseSha256", sha256(db));
            try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
                out.putNextEntry(new ZipEntry("manifest.properties"));
                manifest.store(out, "Safety Ledger portable backup");
                out.closeEntry();
                addFile(out, db, "database.sqlite");
                Path media = extractedRoot.resolve("business_media");
                if (Files.isDirectory(media)) try (var paths = Files.walk(media)) {
                    for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                        addFile(out, file, "business_media/" + media.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
            encryptChunked(zip, destination, PORTABLE_KEY.clone(), PORTABLE_MAGIC);
        } finally { Files.deleteIfExists(zip); }
    }

    private static void decrypt(InputStream raw, Path zip, char[] password, byte[] expectedMagic) throws Exception {
        try (DataInputStream in = new DataInputStream(raw); OutputStream output = new BufferedOutputStream(Files.newOutputStream(zip))) {
            byte[] magic = in.readNBytes(expectedMagic.length);
            if (!Arrays.equals(magic, expectedMagic)) throw new IOException("数据包标识不匹配");
            int version = in.readUnsignedByte();
            if (version < 1 || version > 2) throw new IOException("不支持的数据包版本：" + version);
            byte[] salt = in.readNBytes(16), baseNonce = in.readNBytes(12);
            if (salt.length != 16 || baseNonce.length != 12) throw new EOFException("数据包头不完整");
            SecretKey key = derive(password, salt);
            Arrays.fill(password, '\0');
            if (version == 1) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, baseNonce));
                try (var cipherIn = new javax.crypto.CipherInputStream(in, cipher)) { cipherIn.transferTo(output); }
                catch (IOException invalid) { throw new SecurityException("密码错误或数据包完整性校验失败", invalid); }
                return;
            }
            long counter = 0;
            while (true) {
                int plainLength;
                try { plainLength = in.readInt(); } catch (EOFException e) { throw new EOFException("数据包被截断"); }
                if (plainLength == 0) break;
                if (plainLength < 0 || plainLength > CHUNK_SIZE) throw new IOException("数据包分块长度非法");
                byte[] encrypted = in.readNBytes(plainLength + TAG_BYTES);
                if (encrypted.length != plainLength + TAG_BYTES) throw new EOFException("数据包分块被截断");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, chunkNonce(baseNonce, counter)));
                cipher.updateAAD(chunkAad(expectedMagic, counter, plainLength));
                byte[] plain;
                try { plain = cipher.doFinal(encrypted); }
                catch (Exception invalid) { throw new SecurityException("密码错误或数据包完整性校验失败", invalid); }
                output.write(plain); counter++;
            }
        }
    }

    private static void encryptChunked(Path plain, Path destination, char[] password, byte[] magic) throws Exception {
        byte[] salt = random(16), baseNonce = random(12);
        SecretKey key = derive(password, salt); Arrays.fill(password, '\0');
        Path parent = destination.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(destination)); DataOutputStream data = new DataOutputStream(raw);
             InputStream input = new BufferedInputStream(Files.newInputStream(plain))) {
            raw.write(magic); raw.write(2); raw.write(salt); raw.write(baseNonce);
            byte[] buffer = new byte[CHUNK_SIZE]; long counter = 0;
            for (int n; (n = input.read(buffer)) >= 0;) {
                if (n == 0) continue;
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, chunkNonce(baseNonce, counter)));
                cipher.updateAAD(chunkAad(magic, counter, n));
                byte[] encrypted = cipher.doFinal(buffer, 0, n);
                data.writeInt(n); data.write(encrypted); counter++;
            }
            data.writeInt(0); data.flush();
        }
    }

    private static void verifyAndUnzip(Path zip, Path root) throws Exception {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            ZipEntry manifestEntry = archive.getEntry("manifest.properties"), databaseEntry = archive.getEntry("database.sqlite");
            if (manifestEntry == null || databaseEntry == null) throw new IOException("数据包缺少数据库或清单");
            Properties manifest = new Properties();
            try (InputStream in = archive.getInputStream(manifestEntry)) { manifest.load(in); }
            if (!"safetydata".equals(manifest.getProperty("format"))) throw new IOException("数据包格式标识错误");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = archive.getInputStream(databaseEntry)) { in.transferTo(new DigestOutputStreamSink(digest)); }
            String actual = HexFormat.of().formatHex(digest.digest());
            String expected = manifest.getProperty("databaseSha256", "");
            if (!actual.equalsIgnoreCase(expected)) throw new IOException("数据库 SHA-256 校验失败");
        }
        Files.createDirectories(root); Path normalizedRoot = root.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                Path target = normalizedRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedRoot)) throw new IOException("数据包包含非法路径");
                if (entry.isDirectory()) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    private static SecretKey derive(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES"); }
        finally { spec.clearPassword(); }
    }

    private static byte[] chunkNonce(byte[] base, long counter) {
        byte[] nonce = base.clone(); long seed = ByteBuffer.wrap(base, 4, 8).getLong();
        ByteBuffer.wrap(nonce, 4, 8).putLong(seed + counter); return nonce;
    }
    private static byte[] chunkAad(byte[] magic, long counter, int plainLength) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) { data.write(magic); data.writeLong(counter); data.writeInt(plainLength); }
        return bytes.toByteArray();
    }
    private static byte[] random(int size) { byte[] value = new byte[size]; new SecureRandom().nextBytes(value); return value; }
    private static boolean startsWith(byte[] value, byte[] prefix) { if (value.length < prefix.length) return false; for (int i=0;i<prefix.length;i++) if(value[i]!=prefix[i])return false; return true; }
    private static void addFile(ZipOutputStream out, Path file, String name) throws IOException { out.putNextEntry(new ZipEntry(name)); Files.copy(file, out); out.closeEntry(); }
    public static String sha256(Path path) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256"); try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>0;)d.update(b,0,n);} return HexFormat.of().formatHex(d.digest()); }
    public static void copyTree(Path source, Path target) throws IOException { deleteTree(target); if(!Files.exists(source))return; try(var paths=Files.walk(source)){for(Path p:paths.toList()){Path d=target.resolve(source.relativize(p).toString());if(Files.isDirectory(p))Files.createDirectories(d);else{Files.createDirectories(d.getParent());Files.copy(p,d,StandardCopyOption.REPLACE_EXISTING);}}} }
    public static void deleteTree(Path root) { if(root==null||!Files.exists(root))return; try(var paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){} }

    public static final class ExtractedPackage implements AutoCloseable {
        public final Path root; public final Path database;
        ExtractedPackage(Path root, Path database){this.root=root;this.database=database;}
        @Override public void close(){deleteTree(root);}
    }

    /** OutputStream that only feeds a digest, avoiding a second database copy. */
    private static final class DigestOutputStreamSink extends OutputStream {
        private final MessageDigest digest; DigestOutputStreamSink(MessageDigest digest){this.digest=digest;}
        @Override public void write(int b){digest.update((byte)b);} @Override public void write(byte[] b,int off,int len){digest.update(b,off,len);}
    }
}
