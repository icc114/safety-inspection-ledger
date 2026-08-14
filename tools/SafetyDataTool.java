import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cross-platform reader/writer for Android portable .safetydata files.
 * Run directly with JDK 17: java tools/SafetyDataTool.java info backup.safetydata
 */
public final class SafetyDataTool {
    private static final byte[] MAGIC = "SAFETYLOCAL2".getBytes(StandardCharsets.US_ASCII);
    private static final char[] PORTABLE_KEY = "safety-ledger-portable-backup-v2".toCharArray();
    private static final int CONTAINER_VERSION = 1;
    private static final int ITERATIONS = 310_000;

    private SafetyDataTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        switch (args[0]) {
            case "info" -> inspect(Path.of(args[1]), false);
            case "verify" -> inspect(Path.of(args[1]), true);
            case "extract" -> {
                if (args.length != 3) usage();
                extract(Path.of(args[1]), Path.of(args[2]));
            }
            case "pack" -> {
                if (args.length != 3) usage();
                pack(Path.of(args[1]), Path.of(args[2]));
            }
            default -> usage();
        }
    }

    private static void inspect(Path source, boolean verificationOnly) throws Exception {
        Path zip = decryptToTemporaryZip(source);
        try {
            Properties manifest = verifyZip(zip);
            if (verificationOnly) {
                System.out.println("OK: 数据包格式、AES-GCM 和数据库 SHA-256 校验通过");
                return;
            }
            System.out.println("format=" + manifest.getProperty("format"));
            System.out.println("formatVersion=" + manifest.getProperty("formatVersion"));
            System.out.println("schemaVersion=" + manifest.getProperty("schemaVersion"));
            String createdAt = manifest.getProperty("createdAt", "0");
            try {
                System.out.println("createdAt=" + Instant.ofEpochMilli(Long.parseLong(createdAt)));
            } catch (NumberFormatException invalid) {
                System.out.println("createdAt=" + createdAt);
            }
            System.out.println("databaseSha256=" + manifest.getProperty("databaseSha256"));
            try (ZipFile archive = new ZipFile(zip.toFile())) {
                long media = archive.stream().filter(entry ->
                        !entry.isDirectory() && entry.getName().startsWith("business_media/")).count();
                System.out.println("mediaFiles=" + media);
            }
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static void extract(Path source, Path destination) throws Exception {
        Path zip = decryptToTemporaryZip(source);
        try {
            verifyZip(zip);
            Files.createDirectories(destination);
            Path root = destination.toAbsolutePath().normalize();
            try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
                for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                    Path target = root.resolve(entry.getName()).normalize();
                    if (!target.startsWith(root)) throw new IOException("数据包包含非法路径");
                    if (entry.isDirectory()) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            System.out.println("OK: 已解包到 " + root);
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static void pack(Path sourceDirectory, Path destination) throws Exception {
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path database = source.resolve("database.sqlite");
        if (!Files.isRegularFile(database)) throw new IOException("目录中缺少 database.sqlite");
        Properties manifest = new Properties();
        Path existingManifest = source.resolve("manifest.properties");
        if (Files.isRegularFile(existingManifest)) {
            try (InputStream input = Files.newInputStream(existingManifest)) {
                manifest.load(input);
            }
        }
        manifest.setProperty("format", "safetydata");
        manifest.setProperty("formatVersion", "1");
        manifest.putIfAbsent("schemaVersion", "0");
        manifest.setProperty("createdAt", String.valueOf(System.currentTimeMillis()));
        manifest.setProperty("databaseSha256", sha256(database));

        Path zip = Files.createTempFile("safetydata-pc-pack-", ".zip");
        try {
            try (ZipOutputStream output = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(zip)))) {
                output.putNextEntry(new ZipEntry("manifest.properties"));
                manifest.store(output, "Safety Ledger portable backup");
                output.closeEntry();
                addFile(output, database, "database.sqlite");
                Path media = source.resolve("business_media");
                if (Files.isDirectory(media)) {
                    try (var paths = Files.walk(media)) {
                        for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                            String relative = media.relativize(path).toString().replace('\\', '/');
                            addFile(output, path, "business_media/" + relative);
                        }
                    }
                }
            }
            encryptZip(zip, destination);
            System.out.println("OK: 已生成 " + destination.toAbsolutePath());
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static Path decryptToTemporaryZip(Path source) throws Exception {
        if (!Files.isRegularFile(source)) throw new IOException("找不到数据包：" + source);
        Path zip = Files.createTempFile("safetydata-pc-read-", ".zip");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(source)))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("不是新版便携 .safetydata 数据包；PDF 或旧密码备份不能直接读取");
            }
            int version = input.readUnsignedByte();
            if (version != CONTAINER_VERSION) throw new IOException("不支持的数据包版本：" + version);
            byte[] salt = input.readNBytes(16);
            byte[] iv = input.readNBytes(12);
            if (salt.length != 16 || iv.length != 12) throw new IOException("数据包头不完整");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derive(salt), new GCMParameterSpec(128, iv));
            try (CipherInputStream decrypted = new CipherInputStream(input, cipher);
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(zip))) {
                decrypted.transferTo(output);
            } catch (IOException invalid) {
                throw new IOException("AES-GCM 完整性校验失败，文件可能损坏", invalid);
            }
            return zip;
        } catch (Exception error) {
            Files.deleteIfExists(zip);
            throw error;
        }
    }

    private static void encryptZip(Path zip, Path destination) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, derive(salt), new GCMParameterSpec(128, iv));
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(destination))) {
            raw.write(MAGIC);
            raw.write(CONTAINER_VERSION);
            raw.write(salt);
            raw.write(iv);
            try (CipherOutputStream encrypted = new CipherOutputStream(raw, cipher)) {
                Files.copy(zip, encrypted);
            }
        }
    }

    private static Properties verifyZip(Path zip) throws Exception {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            ZipEntry manifestEntry = archive.getEntry("manifest.properties");
            ZipEntry databaseEntry = archive.getEntry("database.sqlite");
            if (manifestEntry == null || databaseEntry == null) {
                throw new IOException("数据包缺少 manifest.properties 或 database.sqlite");
            }
            Properties manifest = new Properties();
            try (InputStream input = archive.getInputStream(manifestEntry)) {
                manifest.load(input);
            }
            if (!"safetydata".equals(manifest.getProperty("format"))) {
                throw new IOException("manifest 格式标识错误");
            }
            String expected = manifest.getProperty("databaseSha256", "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = archive.getInputStream(databaseEntry)) {
                byte[] buffer = new byte[65_536];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) throw new IOException("数据库 SHA-256 校验失败");
            return manifest;
        }
    }

    private static SecretKey derive(byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(PORTABLE_KEY, salt, ITERATIONS, 256);
        try {
            byte[] value = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(value, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65_536];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void addFile(ZipOutputStream output, Path file, String name) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        Files.copy(file, output);
        output.closeEntry();
    }

    private static void usage() {
        System.err.println("用法：");
        System.err.println("  java tools/SafetyDataTool.java info <文件.safetydata>");
        System.err.println("  java tools/SafetyDataTool.java verify <文件.safetydata>");
        System.err.println("  java tools/SafetyDataTool.java extract <文件.safetydata> <输出目录>");
        System.err.println("  java tools/SafetyDataTool.java pack <已解包目录> <输出文件.safetydata>");
        System.exit(2);
    }
}
