package cn.safetyledger.app.data;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public final class LedgerDatabase extends SQLiteOpenHelper {
    public static final String NAME = "safety_ledger_native_v1.db";
    public static final int VERSION = 3;

    public LedgerDatabase(Context context) { super(context, NAME, null, VERSION); setWriteAheadLoggingEnabled(true); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); db.rawQuery("PRAGMA journal_mode=WAL", null).close();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL, description TEXT NOT NULL)");
        db.execSQL("CREATE TABLE templates(id TEXT PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,device_id TEXT,deleted_at INTEGER)");
        db.execSQL("CREATE TABLE template_items(id TEXT PRIMARY KEY,template_id TEXT NOT NULL REFERENCES templates(id) ON DELETE CASCADE,category TEXT NOT NULL,content TEXT NOT NULL,standard TEXT NOT NULL,sort_order INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,deleted_at INTEGER)");
        db.execSQL("CREATE INDEX idx_template_items_order ON template_items(template_id,sort_order)");
        db.execSQL("CREATE TABLE inspections(id TEXT PRIMARY KEY,template_id TEXT,template_name TEXT NOT NULL,inspection_date TEXT NOT NULL,inspection_time TEXT NOT NULL,inspection_type TEXT NOT NULL,unit_name TEXT NOT NULL DEFAULT '',location TEXT NOT NULL DEFAULT '',on_duty TEXT NOT NULL DEFAULT '',inspector1 TEXT NOT NULL DEFAULT '',inspector2 TEXT NOT NULL DEFAULT '',inspectee TEXT NOT NULL DEFAULT '',conclusion TEXT NOT NULL DEFAULT '',rectification_advice TEXT NOT NULL DEFAULT '',responsible_person TEXT NOT NULL DEFAULT '',deadline TEXT NOT NULL DEFAULT '',rectification_detail TEXT NOT NULL DEFAULT '',recheck_result TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'DRAFT',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,device_id TEXT,deleted_at INTEGER)");
        db.execSQL("CREATE INDEX idx_inspections_date ON inspections(inspection_date,deleted_at)");
        db.execSQL("CREATE TABLE inspection_items(id TEXT PRIMARY KEY,inspection_id TEXT NOT NULL REFERENCES inspections(id) ON DELETE CASCADE,template_item_id TEXT,category TEXT NOT NULL,content TEXT NOT NULL,standard TEXT NOT NULL,result TEXT NOT NULL DEFAULT 'UNSET',problem TEXT NOT NULL DEFAULT '',sort_order INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,deleted_at INTEGER)");
        db.execSQL("CREATE INDEX idx_inspection_items_order ON inspection_items(inspection_id,sort_order)");
        db.execSQL("CREATE TABLE media(id TEXT PRIMARY KEY,inspection_id TEXT NOT NULL REFERENCES inspections(id) ON DELETE CASCADE,inspection_item_id TEXT,category TEXT NOT NULL,local_path TEXT,remote_key TEXT,captured_at INTEGER NOT NULL,location TEXT NOT NULL DEFAULT '',latitude REAL,longitude REAL,sha256 TEXT NOT NULL,mime_type TEXT NOT NULL,size_bytes INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,remote_synced_at INTEGER,local_released_at INTEGER,deleted_at INTEGER)");
        db.execSQL("CREATE INDEX idx_media_inspection ON media(inspection_id,category,deleted_at)");
        db.execSQL("CREATE TABLE signatures(id TEXT PRIMARY KEY,inspection_id TEXT NOT NULL REFERENCES inspections(id) ON DELETE CASCADE,role TEXT NOT NULL,local_path TEXT NOT NULL,sha256 TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,deleted_at INTEGER,UNIQUE(inspection_id,role))");
        db.execSQL("CREATE TABLE app_settings(setting_key TEXT PRIMARY KEY,setting_value TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE sync_providers(id TEXT PRIMARY KEY,provider_type TEXT NOT NULL,display_name TEXT NOT NULL,endpoint TEXT NOT NULL DEFAULT '',username TEXT NOT NULL DEFAULT '',encrypted_secret TEXT NOT NULL DEFAULT '',token_ciphertext TEXT NOT NULL DEFAULT '',sync_space TEXT NOT NULL DEFAULT 'safety-ledger',encryption_secret TEXT NOT NULL DEFAULT '',config_json TEXT NOT NULL DEFAULT '{}',enabled INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        createSyncDevices(db);
        db.execSQL("CREATE TABLE sync_queue(id TEXT PRIMARY KEY,entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,operation TEXT NOT NULL,payload_hash TEXT,attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at INTEGER NOT NULL,created_at INTEGER NOT NULL,last_error TEXT,UNIQUE(entity_type,entity_id,operation))");
        db.execSQL("CREATE TABLE tombstones(id TEXT PRIMARY KEY,entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,deleted_at INTEGER NOT NULL,revision INTEGER NOT NULL,device_id TEXT,synced_at INTEGER,UNIQUE(entity_type,entity_id))");
        db.execSQL("CREATE TABLE conflict_copies(id TEXT PRIMARY KEY,entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,local_revision INTEGER NOT NULL,remote_revision INTEGER NOT NULL,payload_json TEXT NOT NULL,created_at INTEGER NOT NULL,resolved_at INTEGER)");
        db.execSQL("CREATE TABLE holiday_cache(date TEXT PRIMARY KEY,name TEXT NOT NULL,day_type TEXT NOT NULL,source_url TEXT NOT NULL,official_release_date TEXT NOT NULL,fetched_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE archive_index(id TEXT PRIMARY KEY,inspection_id TEXT NOT NULL UNIQUE,inspection_date TEXT NOT NULL,inspection_type TEXT NOT NULL,status TEXT NOT NULL,pdf_local_path TEXT,pdf_remote_key TEXT,pdf_sha256 TEXT,archived_at INTEGER NOT NULL,remote_verified_at INTEGER,source_revision INTEGER NOT NULL)");
        db.execSQL("INSERT INTO schema_migrations VALUES(1,?,?)", new Object[]{System.currentTimeMillis(),"Initial offline-first UUID schema"});
        seedInitialTemplate(db);
        seedOfficialHolidays2026(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Every future version is applied sequentially here and recorded in schema_migrations.
        if (oldVersion < 1) throw new IllegalStateException("Unsupported pre-v1 database");
        if (oldVersion < 2) {
            createSyncDevices(db);
            db.execSQL("INSERT INTO schema_migrations VALUES(2,?,?)",
                    new Object[]{System.currentTimeMillis(), "Add paired-device roles for snapshot sync"});
        }
        if(oldVersion<3){
            // Old releases wrote every revision as 1. Promote records still waiting in the
            // durable outbox so an already-uploaded stale snapshot cannot win after upgrade.
            db.execSQL("UPDATE inspections SET revision=COALESCE(revision,1)+1 WHERE id IN(SELECT entity_id FROM sync_queue WHERE entity_type='inspection') OR id IN(SELECT m.inspection_id FROM media m JOIN sync_queue q ON q.entity_type='media' AND q.entity_id=m.id) OR id IN(SELECT s.inspection_id FROM signatures s JOIN sync_queue q ON q.entity_type='signature' AND q.entity_id=s.id)");
            db.execSQL("UPDATE inspection_items SET revision=COALESCE(revision,1)+1 WHERE inspection_id IN(SELECT id FROM inspections WHERE revision>1)");
            db.execSQL("INSERT INTO schema_migrations VALUES(3,?,?)",new Object[]{System.currentTimeMillis(),"Promote queued record revisions for rollback-safe sync"});
        }
    }

    private static void createSyncDevices(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_devices(device_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,role TEXT NOT NULL DEFAULT 'FIELD',first_seen_at INTEGER NOT NULL,last_seen_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
    }

    private void seedInitialTemplate(SQLiteDatabase db) {
        long now=System.currentTimeMillis(); String tid=UUID.randomUUID().toString();
        db.execSQL("INSERT INTO templates(id,name,category,active,created_at,updated_at) VALUES(?,?,?,?,?,?)",new Object[]{tid,"车棚安全检查","车棚检查",1,now,now});
        String[][] rows={
                {"消防安全","车棚内消防设备设施是否齐全、有效","消防设备数量充足、在有效期内且取用通道畅通"},
                {"排水防汛","车棚内排水沟、排水口、雨水篦子是否畅通","无堵塞、无明显积水，防汛设施可用"},
                {"物资保障","车棚内照明及其他设施是否配齐、完好可用","照明、标识及日常保障物资齐全有效"},
                {"人员值守","值守人员是否到岗，职责是否明确，联系电话及通信设备是否畅通","按排班到岗，职责清楚且联络畅通"},
                {"应急预案","防汛预案和应急处置措施是否落实，现场人员是否熟悉处置及上报流程","预案可查、人员熟悉流程并可及时上报"},
                {"设施安全","消防设施、用电线路及棚体结构是否安全完好，无松动、漏电等隐患","结构牢固、线路规范、无漏电和破损"},
                {"停车秩序","车辆是否分区有序停放，不占压雨篦子，不堵塞出入口和疏散通道","分区停放，出入口、排水和疏散通道畅通"},
                {"通道巡查","出入口、疏散通道是否畅通，是否按要求巡查并如实留存记录","通道畅通，巡查记录真实完整"}
        };
        for(int i=0;i<rows.length;i++) db.execSQL("INSERT INTO template_items(id,template_id,category,content,standard,sort_order,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",new Object[]{UUID.randomUUID().toString(),tid,rows[i][0],rows[i][1],rows[i][2],i+1,now,now});
    }

    private void seedOfficialHolidays2026(SQLiteDatabase db){
        String source="https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm";long now=System.currentTimeMillis();
        String[][] ranges={{"2026-01-01","2026-01-03","元旦"},{"2026-02-15","2026-02-23","春节"},{"2026-04-04","2026-04-06","清明节"},{"2026-05-01","2026-05-05","劳动节"},{"2026-06-19","2026-06-21","端午节"},{"2026-09-25","2026-09-27","中秋节"},{"2026-10-01","2026-10-07","国庆节"}};
        for(String[]r:ranges){java.time.LocalDate a=java.time.LocalDate.parse(r[0]),b=java.time.LocalDate.parse(r[1]);for(java.time.LocalDate d=a;!d.isAfter(b);d=d.plusDays(1))db.execSQL("INSERT INTO holiday_cache VALUES(?,?,?,?,?,?)",new Object[]{d.toString(),r[2],"HOLIDAY",source,"2025-11-04",now});}
        String[][] work={{"2026-01-04","元旦调休上班"},{"2026-02-14","春节调休上班"},{"2026-02-28","春节调休上班"},{"2026-05-09","劳动节调休上班"},{"2026-09-20","国庆节调休上班"},{"2026-10-10","国庆节调休上班"}};
        for(String[]w:work)db.execSQL("INSERT INTO holiday_cache VALUES(?,?,?,?,?,?)",new Object[]{w[0],w[1],"WORKDAY",source,"2025-11-04",now});
    }

    public static ContentValues values(Object... pairs){ContentValues v=new ContentValues();for(int i=0;i<pairs.length;i+=2){String k=(String)pairs[i];Object x=pairs[i+1];if(x==null)v.putNull(k);else if(x instanceof String)v.put(k,(String)x);else if(x instanceof Integer)v.put(k,(Integer)x);else if(x instanceof Long)v.put(k,(Long)x);else if(x instanceof Double)v.put(k,(Double)x);else if(x instanceof byte[])v.put(k,(byte[])x);else throw new IllegalArgumentException(k);}return v;}
    public static String str(Cursor c,String col){int i=c.getColumnIndexOrThrow(col);return c.isNull(i)?null:c.getString(i);}
    public static long lng(Cursor c,String col){return c.getLong(c.getColumnIndexOrThrow(col));}
}
