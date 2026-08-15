package cn.safetyledger.pc;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

/** One-time local migration that redraws system-generated Word forms after layout upgrades. */
public final class WordLayoutMigrator {
    private static final Gson GSON = new Gson();
    private WordLayoutMigrator() {}

    public static int migrate(Path archiveRoot) throws Exception {
        if (archiveRoot == null) return 0;
        Path root = archiveRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return 0;
        Path system = root.resolve(".safety-ledger");
        Files.createDirectories(system);
        Path marker = system.resolve("word-layout-version.txt");
        int current = readVersion(marker);
        if (current >= WordExporter.LAYOUT_VERSION) return 0;

        int updated = 0;
        try (var paths = Files.walk(root)) {
            for (Path json : paths.filter(p -> p.getFileName().toString().equals("record.json")).toList()) {
                Path folder = json.getParent();
                if (folder == null || folder.startsWith(system)) continue;
                ArchiveService.Record record;
                try { record = GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), ArchiveService.Record.class); }
                catch (Exception invalid) { continue; }
                if (record == null) continue;
                attachSignatures(record, folder.resolve("签名"));

                Path main = folder.resolve("检查记录.docx");
                Path hash = folder.resolve(".system-docx.sha256");
                Path output = main;
                boolean safeToReplace = !Files.exists(main);
                if (Files.isRegularFile(main) && Files.isRegularFile(hash)) {
                    String expected = Files.readString(hash, StandardCharsets.UTF_8).trim();
                    String actual = DataPackageCodec.sha256(main);
                    safeToReplace = actual.equalsIgnoreCase(expected);
                }
                if (!safeToReplace) {
                    output = folder.resolve("检查记录-系统更新-"
                            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".docx");
                }
                WordExporter.write(record, output);
                if (output.equals(main)) {
                    Files.writeString(hash, DataPackageCodec.sha256(main), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                updated++;
            }
        }
        Files.writeString(marker, String.valueOf(WordExporter.LAYOUT_VERSION), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return updated;
    }

    private static void attachSignatures(ArchiveService.Record record, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                String name = file.getFileName().toString().toUpperCase();
                if (name.startsWith("INSPECTOR1.")) record.signatures.put("INSPECTOR1", file);
                else if (name.startsWith("INSPECTOR2.")) record.signatures.put("INSPECTOR2", file);
                else if (name.startsWith("INSPECTEE.")) record.signatures.put("INSPECTEE", file);
            }
        } catch (Exception ignored) {}
    }

    private static int readVersion(Path marker) {
        try { return Integer.parseInt(Files.readString(marker, StandardCharsets.UTF_8).trim()); }
        catch (Exception ignored) { return 0; }
    }
}
