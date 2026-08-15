package cn.safetyledger.pc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Converts synchronized Android snapshots into a permanent, human-readable Windows archive. */
public final class ArchiveService {
    private final Path root;
    private final Path systemDir;
    private final Path indexFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ArchiveService(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.systemDir = this.root.resolve(".safety-ledger");
        this.indexFile = systemDir.resolve("index.properties");
        Files.createDirectories(systemDir);
    }

    public List<Record> process(DataPackageCodec.ExtractedPackage pkg, String sourceName) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Properties index = load(indexFile);
        List<Record> exported = new ArrayList<>();
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + pkg.database.toAbsolutePath())) {
            Map<String,Long> tombstones = tombstones(db);
            Map<String,Integer> sequence = dailySequence(db);
            try (PreparedStatement ps = db.prepareStatement("SELECT * FROM inspections WHERE status<>'DRAFT' ORDER BY inspection_date,inspection_time,created_at"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Record record = readRecord(db, rs, pkg.root, sequence.getOrDefault(rs.getString("id"), 1));
                    Long tombstone = tombstones.get(record.id);
                    record.deleted = rs.getObject("deleted_at") != null || (tombstone != null && tombstone >= record.updatedAt);
                    long known = longValue(index.getProperty(record.id + ".updated"), -1);
                    String previous = index.getProperty(record.id + ".path", "");
                    Path folder = folder(record);
                    if (!previous.isBlank()) {
                        Path old = root.resolve(previous).normalize();
                        if (!old.equals(folder) && Files.isDirectory(old) && !Files.exists(folder)) {
                            Files.createDirectories(folder.getParent());
                            try { Files.move(old, folder, StandardCopyOption.ATOMIC_MOVE); }
                            catch (Exception moveFailed) { copyDirectory(old, folder); }
                        }
                    }
                    if (record.updatedAt >= known || !Files.isDirectory(folder)) {
                        writeRecord(record, folder, sourceName);
                        index.setProperty(record.id + ".updated", String.valueOf(record.updatedAt));
                        index.setProperty(record.id + ".path", root.relativize(folder).toString());
                    } else if (record.deleted) {
                        Files.writeString(folder.resolve("已从移动端删除.txt"), "该记录已从移动端同步删除，但电脑本地资料库保留历史副本。\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    }
                    exported.add(record);
                }
            }
        }
        store(indexFile, index);
        return exported;
    }

    public List<IndexEntry> listIndex() throws IOException {
        Properties index = load(indexFile); List<IndexEntry> out = new ArrayList<>();
        for (String key:index.stringPropertyNames()) if (key.endsWith(".path")) {
            String id=key.substring(0,key.length()-5);Path folder=root.resolve(index.getProperty(key));Path json=folder.resolve("record.json");
            if(!Files.isRegularFile(json))continue;
            try { Record r=gson.fromJson(Files.readString(json,StandardCharsets.UTF_8),Record.class); out.add(new IndexEntry(id,r.date,r.time,r.templateName,r.location,r.status,folder)); }
            catch(Exception ignored){}
        }
        out.sort(Comparator.comparing((IndexEntry e)->e.date).thenComparing(e->e.time).reversed()); return out;
    }

    private Record readRecord(Connection db, ResultSet rs, Path packageRoot, int sequence) throws Exception {
        Record r=new Record();
        r.id=rs.getString("id");r.templateName=nvl(rs.getString("template_name"),"安全检查");r.date=rs.getString("inspection_date");r.time=nvl(rs.getString("inspection_time"),"");
        r.type=nvl(rs.getString("inspection_type"),"");r.location=nvl(rs.getString("location"),"");r.status=nvl(rs.getString("status"),"");r.rectification=nvl(rs.getString("rectification_detail"),"");r.recheck=nvl(rs.getString("recheck_result"),"");r.updatedAt=rs.getLong("updated_at");r.sequence=sequence;
        try(PreparedStatement ps=db.prepareStatement("SELECT * FROM inspection_items WHERE inspection_id=? AND deleted_at IS NULL ORDER BY sort_order")){ps.setString(1,r.id);try(ResultSet q=ps.executeQuery()){while(q.next()){Item i=new Item();i.category=nvl(q.getString("category"),"");i.content=nvl(q.getString("content"),"");i.standard=nvl(q.getString("standard"),"");i.result=nvl(q.getString("result"),"");i.problem=nvl(q.getString("problem"),"");i.order=q.getInt("sort_order");r.items.add(i);}}}
        try(PreparedStatement ps=db.prepareStatement("SELECT * FROM media WHERE inspection_id=? AND deleted_at IS NULL ORDER BY captured_at,id")){ps.setString(1,r.id);try(ResultSet q=ps.executeQuery()){while(q.next()){Media m=new Media();m.id=q.getString("id");m.category=nvl(q.getString("category"),"SCENE");m.capturedAt=q.getLong("captured_at");m.location=nvl(q.getString("location"),"");m.source=packageRoot.resolve("business_media").resolve(r.id).resolve(m.id+".jpg");r.media.add(m);}}}
        try(PreparedStatement ps=db.prepareStatement("SELECT role,local_path FROM signatures WHERE inspection_id=? AND deleted_at IS NULL")){ps.setString(1,r.id);try(ResultSet q=ps.executeQuery()){while(q.next()){String role=q.getString(1);String stored=nvl(q.getString(2),"");String name=stored.isBlank()?"signature-"+role+".png":Path.of(stored).getFileName().toString();Path source=packageRoot.resolve("business_media").resolve(r.id).resolve(name);r.signatures.put(role,source);}}}
        return r;
    }

    private Map<String,Integer> dailySequence(Connection db) throws SQLException {
        Map<String,Integer> out=new HashMap<>();String current="";int n=0;
        try(PreparedStatement ps=db.prepareStatement("SELECT id,inspection_date FROM inspections WHERE status<>'DRAFT' ORDER BY inspection_date,inspection_time,created_at,id");ResultSet rs=ps.executeQuery()){
            while(rs.next()){String date=rs.getString(2);if(!Objects.equals(current,date)){current=date;n=0;}out.put(rs.getString(1),++n);}
        }return out;
    }

    private Map<String,Long> tombstones(Connection db) {
        Map<String,Long> out=new HashMap<>();try(PreparedStatement ps=db.prepareStatement("SELECT entity_id,deleted_at FROM tombstones WHERE entity_type='inspection'");ResultSet rs=ps.executeQuery()){while(rs.next())out.merge(rs.getString(1),rs.getLong(2),Math::max);}catch(SQLException ignored){}return out;
    }

    private Path folder(Record r) {
        LocalDate date;try{date=LocalDate.parse(r.date);}catch(Exception e){date=LocalDate.of(1970,1,1);}
        String time=r.time==null?"":r.time.replace(":","");
        String name=String.format(Locale.ROOT,"%03d_%s_%s_%s",r.sequence,time,sanitize(r.templateName),sanitize(r.location));
        return root.resolve(String.valueOf(date.getYear())).resolve(String.format(Locale.ROOT,"%02d",date.getMonthValue())).resolve(r.date).resolve(trim(name,96));
    }

    private void writeRecord(Record r, Path folder, String sourceName) throws Exception {
        Files.createDirectories(folder);
        Path check=folder.resolve("检查照片"),rect=folder.resolve("整改照片"),recheck=folder.resolve("复查照片");Files.createDirectories(check);Files.createDirectories(rect);Files.createDirectories(recheck);
        int a=0,b=0,c=0;for(Media m:r.media){if(!Files.isRegularFile(m.source))continue;Path target;String stamp=formatTime(m.capturedAt);if("RECTIFICATION".equals(m.category))target=rect.resolve(String.format(Locale.ROOT,"%03d_整改照片_%s.jpg",++b,stamp));else if("RECHECK".equals(m.category))target=recheck.resolve(String.format(Locale.ROOT,"%03d_复查照片_%s.jpg",++c,stamp));else target=check.resolve(String.format(Locale.ROOT,"%03d_检查照片_%s.jpg",++a,stamp));Files.copy(m.source,target,StandardCopyOption.REPLACE_EXISTING);}
        Path sigDir=folder.resolve("签名");Files.createDirectories(sigDir);for(var e:r.signatures.entrySet())if(Files.isRegularFile(e.getValue())){Path target=sigDir.resolve(e.getKey()+extension(e.getValue()));Files.copy(e.getValue(),target,StandardCopyOption.REPLACE_EXISTING);e.setValue(target);}
        Path main=folder.resolve("检查记录.docx"),hash=folder.resolve(".system-docx.sha256");Path output=main;
        if(Files.isRegularFile(main)&&Files.isRegularFile(hash)){
            String expected=Files.readString(hash).trim();String actual=DataPackageCodec.sha256(main);
            if(!actual.equalsIgnoreCase(expected))output=folder.resolve("检查记录-系统更新-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".docx");
        }
        WordExporter.write(r,output);String digest=DataPackageCodec.sha256(output);if(output.equals(main))Files.writeString(hash,digest,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(folder.resolve("record.json"),gson.toJson(r),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(folder.resolve("同步来源.txt"),"来源："+sourceName+"\n最后同步："+LocalDateTime.now()+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        Path deleted=folder.resolve("已从移动端删除.txt");if(r.deleted)Files.writeString(deleted,"该记录已从移动端同步删除，但电脑本地资料库保留历史副本。\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);else Files.deleteIfExists(deleted);
    }

    private static Properties load(Path p)throws IOException{Properties x=new Properties();if(Files.isRegularFile(p))try(InputStream in=Files.newInputStream(p)){x.load(in);}return x;}
    private static void store(Path p,Properties x)throws IOException{Files.createDirectories(p.getParent());try(OutputStream out=Files.newOutputStream(p)){x.store(out,"Safety Ledger PC archive index");}}
    private static long longValue(String v,long d){try{return Long.parseLong(v);}catch(Exception e){return d;}}
    private static String nvl(String v,String d){return v==null?d:v;}
    private static String extension(Path p){String n=p.getFileName().toString();int i=n.lastIndexOf('.');return i>=0?n.substring(i):".png";}
    private static String formatTime(long millis){try{return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));}catch(Exception e){return String.valueOf(millis);}}
    private static String sanitize(String v){String s=nvl(v,"").replaceAll("[\\\\/:*?\"<>|\\r\\n]+","_").trim();return s.isBlank()?"未命名":s;}
    private static String trim(String s,int n){return s.length()<=n?s:s.substring(0,n);}
    private static void copyDirectory(Path src,Path dst)throws IOException{try(var paths=Files.walk(src)){for(Path p:paths.toList()){Path t=dst.resolve(src.relativize(p).toString());if(Files.isDirectory(p))Files.createDirectories(t);else{Files.createDirectories(t.getParent());Files.copy(p,t,StandardCopyOption.REPLACE_EXISTING);}}}}

    public static final class Record {
        public String id="",templateName="",date="",time="",type="",location="",status="",rectification="",recheck="";public long updatedAt;public int sequence;public boolean deleted;
        public final List<Item> items=new ArrayList<>();public final List<Media> media=new ArrayList<>();public final Map<String,Path> signatures=new LinkedHashMap<>();
        public Path signature(String role){return signatures.get(role);}
    }
    public static final class Item { public String category="",content="",standard="",result="",problem="";public int order; }
    public static final class Media { public String id="",category="",location="";public long capturedAt;public Path source; }
    public static final class IndexEntry { public final String id,date,time,title,location,status;public final Path folder;IndexEntry(String id,String date,String time,String title,String location,String status,Path folder){this.id=id;this.date=date;this.time=time;this.title=title;this.location=location;this.status=status;this.folder=folder;} }
}
