from pathlib import Path

p = Path('desktop/src/main/java/cn/safetyledger/pc/ArchiveService.java')
text = p.read_text(encoding='utf-8')
old = '''        store(indexFile, index);
        return exported;
    }
'''
new = '''        // A synchronized Android snapshot may contain only the tombstone after the business
        // row has already been physically removed. Preserve the Windows archive and mark its
        // existing folder instead of silently losing the deletion state.
        for (Map.Entry<String,Long> deleted : collectedTombstones.entrySet()) {
            String relative = index.getProperty(deleted.getKey() + ".path", "");
            if (relative.isBlank()) continue;
            Path oldFolder = root.resolve(relative).normalize();
            if (!oldFolder.startsWith(root) || !Files.isDirectory(oldFolder)) continue;
            Files.writeString(oldFolder.resolve("已从移动端删除.txt"),
                    "该记录已从移动端同步删除，但电脑本地资料库保留历史副本。\\n删除时间："
                            + Instant.ofEpochMilli(deleted.getValue()).atZone(ZoneId.systemDefault()) + "\\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        store(indexFile, index);
        return exported;
    }
'''
if old not in text:
    raise SystemExit('ArchiveService process tail not found')
text = text.replace(old, new, 1)
old = '''        Map<String,Long> tombstones = tombstones(db);
            Map<String,Integer> sequence = dailySequence(db);
'''
new = '''        Map<String,Long> tombstones = tombstones(db);
            collectedTombstones.putAll(tombstones);
            Map<String,Integer> sequence = dailySequence(db);
'''
if old not in text:
    raise SystemExit('ArchiveService tombstone map block not found')
# Add accumulator before opening DB so it can be used after the try block.
text = text.replace('''        List<Record> exported = new ArrayList<>();
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + pkg.database.toAbsolutePath())) {
''', '''        List<Record> exported = new ArrayList<>();
        Map<String,Long> collectedTombstones = new HashMap<>();
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + pkg.database.toAbsolutePath())) {
''', 1)
text = text.replace(old, new, 1)
text = text.replace('''        public final List<Item> items=new ArrayList<>();public final List<Media> media=new ArrayList<>();public final Map<String,Path> signatures=new LinkedHashMap<>();
''', '''        public final List<Item> items=new ArrayList<>();public final List<Media> media=new ArrayList<>();public final transient Map<String,Path> signatures=new LinkedHashMap<>();
''', 1)
text = text.replace('''    public static final class Media { public String id="",category="",location="";public long capturedAt;public Path source; }
''', '''    public static final class Media { public String id="",category="",location="";public long capturedAt;public transient Path source; }
''', 1)
p.write_text(text, encoding='utf-8')
print('PC runtime fixes applied')
