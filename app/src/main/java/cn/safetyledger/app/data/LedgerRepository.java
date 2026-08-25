package cn.safetyledger.app.data;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import cn.safetyledger.app.data.Entities.*;
import cn.safetyledger.app.sync.CloudSyncScheduler;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class LedgerRepository {
    private final LedgerDatabase helper;
    private final Context context;
    public LedgerRepository(Context c){context=c.getApplicationContext();helper=((cn.safetyledger.app.SafetyLedgerApp)context).db();}
    public SQLiteDatabase raw(){return helper.getWritableDatabase();}

    public List<Template> templates(boolean includeInactive){
        List<Template> out=new ArrayList<>();
        try(Cursor c=raw().query("templates",null,"deleted_at IS NULL"+(includeInactive?"":" AND active=1"),null,null,null,"active DESC,updated_at DESC")){
            while(c.moveToNext()){Template t=new Template();t.id=LedgerDatabase.str(c,"id");t.name=LedgerDatabase.str(c,"name");t.category=LedgerDatabase.str(c,"category");t.active=LedgerDatabase.lng(c,"active")==1;t.updatedAt=LedgerDatabase.lng(c,"updated_at");t.items.addAll(templateItems(t.id));out.add(t);}
        } return out;
    }
    public Template template(String id){for(Template t:templates(true))if(t.id.equals(id))return t;return null;}
    public List<String> inspectionTypes(){List<String>out=new ArrayList<>();try(Cursor c=raw().rawQuery("SELECT DISTINCT inspection_type FROM inspections WHERE deleted_at IS NULL AND inspection_type<>'' ORDER BY inspection_type",null)){while(c.moveToNext())out.add(c.getString(0));}return out;}
    public List<TemplateItem> templateItems(String tid){List<TemplateItem> out=new ArrayList<>();try(Cursor c=raw().query("template_items",null,"template_id=? AND deleted_at IS NULL",new String[]{tid},null,null,"sort_order")){while(c.moveToNext()){TemplateItem x=new TemplateItem();x.id=LedgerDatabase.str(c,"id");x.templateId=tid;x.category=LedgerDatabase.str(c,"category");x.content=LedgerDatabase.str(c,"content");x.standard=LedgerDatabase.str(c,"standard");x.order=(int)LedgerDatabase.lng(c,"sort_order");x.active=LedgerDatabase.lng(c,"active")==1;out.add(x);}}return out;}
    public String saveTemplate(String id,String name,String category,boolean active){long now=System.currentTimeMillis();if(id==null)id=UUID.randomUUID().toString();ContentValues v=LedgerDatabase.values("id",id,"name",name,"category",category,"active",active?1:0,"created_at",now,"updated_at",now,"revision",1);raw().insertWithOnConflict("templates",null,v,SQLiteDatabase.CONFLICT_IGNORE);v.remove("created_at");v.remove("id");raw().update("templates",v,"id=?",new String[]{id});queue("template",id,"UPSERT");return id;}
    public void deleteTemplate(String id){long n=System.currentTimeMillis();raw().update("templates",LedgerDatabase.values("deleted_at",n,"active",0,"updated_at",n),"id=?",new String[]{id});tombstone("template",id);}
    public String saveTemplateItem(String id,String tid,String cat,String content,String standard,int order){long now=System.currentTimeMillis();if(id==null)id=UUID.randomUUID().toString();ContentValues v=LedgerDatabase.values("id",id,"template_id",tid,"category",cat,"content",content,"standard",standard,"sort_order",order,"active",1,"created_at",now,"updated_at",now);raw().insertWithOnConflict("template_items",null,v,SQLiteDatabase.CONFLICT_REPLACE);queue("template_item",id,"UPSERT");return id;}
    public void deleteTemplateItem(String id){String tid=null;try(Cursor c=raw().query("template_items",new String[]{"template_id"},"id=?",new String[]{id},null,null,null)){if(c.moveToFirst())tid=c.getString(0);}long n=System.currentTimeMillis();raw().update("template_items",LedgerDatabase.values("deleted_at",n,"updated_at",n),"id=?",new String[]{id});tombstone("template_item",id);if(tid!=null){List<TemplateItem>left=templateItems(tid);for(int i=0;i<left.size();i++)raw().update("template_items",LedgerDatabase.values("sort_order",i+1,"updated_at",n),"id=?",new String[]{left.get(i).id});}}
    public void reorderItem(String tid,String id,int direction){List<TemplateItem> list=templateItems(tid);for(int i=0;i<list.size();i++)if(list.get(i).id.equals(id)){int j=i+direction;if(j<0||j>=list.size())return;SQLiteDatabase d=raw();d.beginTransaction();try{d.update("template_items",LedgerDatabase.values("sort_order",list.get(j).order),"id=?",new String[]{id});d.update("template_items",LedgerDatabase.values("sort_order",list.get(i).order),"id=?",new String[]{list.get(j).id});d.setTransactionSuccessful();}finally{d.endTransaction();}return;}}

    public Inspection newInspection(String templateId){Template t=template(templateId);if(t==null)throw new IllegalArgumentException("模板不存在");LocalDateTime n=LocalDateTime.now();Inspection x=new Inspection();x.id=UUID.randomUUID().toString();x.deviceId=ensureDeviceId();x.templateId=t.id;x.templateName=t.name;x.date=n.toLocalDate().toString();x.time=n.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));x.type=t.category;x.unit="";x.location="";x.onDuty="";x.inspector1="";x.inspector2="";x.inspectee="";x.conclusion="";x.advice="";x.responsible="";x.deadline="";x.rectification="";x.recheck="";x.status="DRAFT";x.createdAt=x.updatedAt=System.currentTimeMillis();
        SQLiteDatabase d=raw();d.beginTransaction();try{d.insertOrThrow("inspections",null,inspectionValues(x,true));for(TemplateItem ti:t.items)if(ti.active){InspectionItem ii=new InspectionItem();ii.id=UUID.randomUUID().toString();ii.inspectionId=x.id;ii.templateItemId=ti.id;ii.category=ti.category;ii.content=ti.content;ii.standard=ti.standard;ii.result="UNSET";ii.problem="";ii.order=ti.order;d.insertOrThrow("inspection_items",null,itemValues(ii,true));}d.setTransactionSuccessful();}finally{d.endTransaction();}return inspection(x.id);}
    public void discardDraft(String id){if(id==null||id.isBlank())return;Inspection x=inspection(id);if(x==null||!"DRAFT".equals(x.status))return;SQLiteDatabase d=raw();d.beginTransaction();try{d.delete("media","inspection_id=?",new String[]{id});d.delete("signatures","inspection_id=?",new String[]{id});d.delete("inspections","id=?",new String[]{id});d.setTransactionSuccessful();}finally{d.endTransaction();}}
    public void saveInspection(Inspection x){
        x.updatedAt=System.currentTimeMillis();SQLiteDatabase d=raw();d.beginTransaction();
        try{
            d.update("inspections",inspectionValues(x,false),"id=?",new String[]{x.id});
            d.execSQL("UPDATE inspections SET revision=COALESCE(revision,1)+1 WHERE id=?",new Object[]{x.id});
            for(InspectionItem i:x.items){
                d.update("inspection_items",itemValues(i,false),"id=?",new String[]{i.id});
                d.execSQL("UPDATE inspection_items SET revision=COALESCE(revision,1)+1 WHERE id=?",new Object[]{i.id});
            }
            queueRow(d,"inspection",x.id,"UPSERT",x.updatedAt);d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        CloudSyncScheduler.scheduleSoon(context);
    }
    private ContentValues inspectionValues(Inspection x,boolean create){ContentValues v=LedgerDatabase.values("template_id",x.templateId,"template_name",x.templateName,"inspection_date",x.date,"inspection_time",x.time,"inspection_type",x.type,"unit_name",x.unit,"location",x.location,"on_duty",x.onDuty,"inspector1",x.inspector1,"inspector2",x.inspector2,"inspectee",x.inspectee,"conclusion",x.conclusion,"rectification_advice",x.advice,"responsible_person",x.responsible,"deadline",x.deadline,"rectification_detail",x.rectification,"recheck_result",x.recheck,"status",x.status,"updated_at",x.updatedAt);if(x.deviceId!=null&&!x.deviceId.isBlank())v.put("device_id",x.deviceId);if(create){v.put("id",x.id);v.put("created_at",x.createdAt);v.put("revision",1);}return v;}
    private ContentValues itemValues(InspectionItem x,boolean create){long n=System.currentTimeMillis();ContentValues v=LedgerDatabase.values("inspection_id",x.inspectionId,"template_item_id",x.templateItemId,"category",x.category,"content",x.content,"standard",x.standard,"result",x.result,"problem",x.problem,"sort_order",x.order,"updated_at",n);if(create){v.put("id",x.id);v.put("created_at",n);v.put("revision",1);}return v;}
    public Inspection inspection(String id){Inspection x=null;try(Cursor c=raw().query("inspections",null,"id=?",new String[]{id},null,null,null)){if(c.moveToFirst())x=readInspection(c);}if(x==null)return null;try(Cursor c=raw().query("inspection_items",null,"inspection_id=? AND deleted_at IS NULL",new String[]{id},null,null,"sort_order")){while(c.moveToNext())x.items.add(readItem(c));}x.media.addAll(media(id));return x;}
    private Inspection readInspection(Cursor c){Inspection x=new Inspection();x.id=s(c,"id");x.templateId=s(c,"template_id");x.templateName=s(c,"template_name");x.date=s(c,"inspection_date");x.time=s(c,"inspection_time");x.type=s(c,"inspection_type");x.unit=s(c,"unit_name");x.location=s(c,"location");x.onDuty=s(c,"on_duty");x.inspector1=s(c,"inspector1");x.inspector2=s(c,"inspector2");x.inspectee=s(c,"inspectee");x.conclusion=s(c,"conclusion");x.advice=s(c,"rectification_advice");x.responsible=s(c,"responsible_person");x.deadline=s(c,"deadline");x.rectification=s(c,"rectification_detail");x.recheck=s(c,"recheck_result");x.status=s(c,"status");x.deviceId=s(c,"device_id");x.createdAt=LedgerDatabase.lng(c,"created_at");x.updatedAt=LedgerDatabase.lng(c,"updated_at");int di=c.getColumnIndex("deleted_at");x.deletedAt=c.isNull(di)?null:c.getLong(di);return x;}
    private InspectionItem readItem(Cursor c){InspectionItem x=new InspectionItem();x.id=s(c,"id");x.inspectionId=s(c,"inspection_id");x.templateItemId=s(c,"template_item_id");x.category=s(c,"category");x.content=s(c,"content");x.standard=s(c,"standard");x.result=s(c,"result");x.problem=s(c,"problem");x.order=(int)LedgerDatabase.lng(c,"sort_order");return x;}
    private String s(Cursor c,String n){String x=LedgerDatabase.str(c,n);return x==null?"":x;}
    public Page<Inspection> list(String from,String to,String type,boolean trash,int page,int size){return list(from,to,type,null,trash,page,size);}
    public Page<Inspection> list(String from,String to,String type,String status,boolean trash,int page,int size){Page<Inspection> p=new Page<>();p.page=page;p.pageSize=size;List<String> args=new ArrayList<>();StringBuilder w=new StringBuilder(trash?"deleted_at IS NOT NULL":"deleted_at IS NULL");if(from!=null){w.append(" AND inspection_date>=?");args.add(from);}if(to!=null){w.append(" AND inspection_date<=?");args.add(to);}if(type!=null&&!type.isBlank()){w.append(" AND inspection_type=?");args.add(type);}if(status!=null&&!status.isBlank()){w.append(" AND status=?");args.add(status);}try(Cursor c=raw().rawQuery("SELECT count(*) FROM inspections WHERE "+w,args.toArray(new String[0]))){if(c.moveToFirst())p.total=c.getInt(0);}try(Cursor c=raw().query("inspections",null,w.toString(),args.toArray(new String[0]),null,null,"inspection_date DESC,inspection_time DESC",size+" OFFSET "+Math.max(0,(page-1)*size))){while(c.moveToNext())p.rows.add(readInspection(c));}return p;}
    public Set<String> markedDates(String month){Set<String>s=new HashSet<>();try(Cursor c=raw().rawQuery("SELECT DISTINCT inspection_date FROM inspections WHERE deleted_at IS NULL AND inspection_date LIKE ?",new String[]{month+"%"})){while(c.moveToNext())s.add(c.getString(0));}return s;}
    public Map<String,String[]> holidays(String month){Map<String,String[]>m=new HashMap<>();try(Cursor c=raw().query("holiday_cache",new String[]{"date","name","day_type"},"date LIKE ?",new String[]{month+"%"},null,null,"date")){while(c.moveToNext())m.put(c.getString(0),new String[]{c.getString(1),c.getString(2)});}return m;}
    public long holidayLastFetchedAt(int year){try(Cursor c=raw().rawQuery("SELECT COALESCE(MAX(fetched_at),0) FROM holiday_cache WHERE date LIKE ?",new String[]{year+"-%"})){return c.moveToFirst()?c.getLong(0):0L;}}
    public void replaceHolidayYear(int year,Map<String,String[]>rows,String sourceUrl){SQLiteDatabase d=raw();long now=System.currentTimeMillis();d.beginTransaction();try{d.delete("holiday_cache","date LIKE ?",new String[]{year+"-%"});for(Map.Entry<String,String[]>e:rows.entrySet()){String[]v=e.getValue();d.insertWithOnConflict("holiday_cache",null,LedgerDatabase.values("date",e.getKey(),"name",v[0],"day_type",v[1],"source_url",sourceUrl==null?"":sourceUrl,"official_release_date","","fetched_at",now),SQLiteDatabase.CONFLICT_REPLACE);}d.setTransactionSuccessful();}finally{d.endTransaction();}}
    public List<Inspection> exportRange(String from,String to){Page<Inspection> p=list(from,to,null,false,1,100000);List<Inspection> all=new ArrayList<>();for(Inspection i:p.rows)all.add(inspection(i.id));return all;}
    public void softDelete(String id){long n=System.currentTimeMillis();raw().update("inspections",LedgerDatabase.values("deleted_at",n,"updated_at",n),"id=?",new String[]{id});raw().execSQL("UPDATE inspections SET revision=COALESCE(revision,1)+1 WHERE id=?",new Object[]{id});queue("inspection",id,"DELETE");}
    public void restore(String id){long n=System.currentTimeMillis();raw().update("inspections",LedgerDatabase.values("deleted_at",null,"updated_at",n),"id=?",new String[]{id});raw().execSQL("UPDATE inspections SET revision=COALESCE(revision,1)+1 WHERE id=?",new Object[]{id});queue("inspection",id,"UPSERT");}
    public void permanentDelete(String id){SQLiteDatabase d=raw();d.beginTransaction();try{d.delete("inspections","id=?",new String[]{id});tombstone("inspection",id);d.setTransactionSuccessful();}finally{d.endTransaction();}}
    public void permanentDeleteAt(String id,long deletedAt){
        SQLiteDatabase d=raw();d.beginTransaction();
        try{
            d.delete("inspections","id=?",new String[]{id});
            ContentValues tombstone=LedgerDatabase.values(
                    "id",UUID.randomUUID().toString(),
                    "entity_type","inspection",
                    "entity_id",id,
                    "deleted_at",deletedAt,
                    "revision",1);
            d.insertWithOnConflict("tombstones",null,tombstone,SQLiteDatabase.CONFLICT_REPLACE);
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    public void clearInspectionTombstone(String id){raw().delete("tombstones","entity_type='inspection' AND entity_id=?",new String[]{id});}
    public void clearInspectionTombstoneAndRestore(String id){
        long now=System.currentTimeMillis();SQLiteDatabase d=raw();d.beginTransaction();
        try{
            d.delete("tombstones","entity_type='inspection' AND entity_id=?",new String[]{id});
            d.update("inspections",LedgerDatabase.values("deleted_at",null,"updated_at",now),"id=?",new String[]{id});
            d.execSQL("UPDATE inspections SET revision=COALESCE(revision,1)+1 WHERE id=?",new Object[]{id});
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        queue("inspection",id,"UPSERT");
    }
    private void tombstone(String type,String id){long n=System.currentTimeMillis();raw().insertWithOnConflict("tombstones",null,LedgerDatabase.values("id",UUID.randomUUID().toString(),"entity_type",type,"entity_id",id,"deleted_at",n,"revision",1),SQLiteDatabase.CONFLICT_REPLACE);queue(type,id,"DELETE");}
    private void queue(String type,String id,String op){long n=System.currentTimeMillis();queueRow(raw(),type,id,op,n);CloudSyncScheduler.scheduleSoon(context);}
    private void queueRow(SQLiteDatabase d,String type,String id,String op,long now){
        d.delete("sync_queue","entity_type=? AND entity_id=?",new String[]{type,id});
        d.insertOrThrow("sync_queue",null,LedgerDatabase.values("id",UUID.randomUUID().toString(),"entity_type",type,"entity_id",id,"operation",op,"attempts",0,"next_attempt_at",now,"created_at",now));
    }
    public void queueDeviceRole(String deviceId){queue("sync_device",deviceId,"UPSERT");}
    public void addMedia(Media m){long n=System.currentTimeMillis();SQLiteDatabase d=raw();d.beginTransaction();try{d.insertOrThrow("media",null,LedgerDatabase.values("id",m.id,"inspection_id",m.inspectionId,"inspection_item_id",m.itemId,"category",m.category,"local_path",m.localPath,"remote_key",m.remoteKey,"captured_at",m.capturedAt,"location",m.location,"latitude",m.latitude,"longitude",m.longitude,"sha256",m.sha256,"mime_type",m.mime,"size_bytes",m.size,"created_at",n,"updated_at",n,"revision",1));d.execSQL("UPDATE inspections SET updated_at=?,revision=COALESCE(revision,1)+1 WHERE id=?",new Object[]{n,m.inspectionId});queueRow(d,"media",m.id,"UPSERT",n);d.setTransactionSuccessful();}finally{d.endTransaction();}CloudSyncScheduler.scheduleSoon(context);}
    public List<Media> media(String iid){List<Media>o=new ArrayList<>();try(Cursor c=raw().query("media",null,"inspection_id=? AND deleted_at IS NULL",new String[]{iid},null,null,"captured_at")){while(c.moveToNext()){Media m=new Media();m.id=s(c,"id");m.inspectionId=iid;m.itemId=s(c,"inspection_item_id");m.category=s(c,"category");m.localPath=s(c,"local_path");m.remoteKey=s(c,"remote_key");m.location=s(c,"location");m.sha256=s(c,"sha256");m.mime=s(c,"mime_type");m.capturedAt=LedgerDatabase.lng(c,"captured_at");m.size=LedgerDatabase.lng(c,"size_bytes");int la=c.getColumnIndex("latitude"),lo=c.getColumnIndex("longitude");m.latitude=c.isNull(la)?null:c.getDouble(la);m.longitude=c.isNull(lo)?null:c.getDouble(lo);o.add(m);}}return o;}
    public void saveSignature(Signature s){
        long n=System.currentTimeMillis(),created=n,revision=1;SQLiteDatabase d=raw();
        try(Cursor c=d.rawQuery("SELECT id,created_at,COALESCE(revision,1) FROM signatures WHERE inspection_id=? AND role=?",new String[]{s.inspectionId,s.role})){
            if(c.moveToFirst()){s.id=c.getString(0);created=c.getLong(1);revision=c.getLong(2)+1;}
        }
        d.beginTransaction();try{
            ContentValues v=LedgerDatabase.values("id",s.id,"inspection_id",s.inspectionId,"role",s.role,"local_path",s.path,"sha256",s.sha256,"created_at",created,"updated_at",n,"revision",revision);
            d.insertWithOnConflict("signatures",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            d.execSQL("UPDATE inspections SET updated_at=?,revision=COALESCE(revision,1)+1 WHERE id=?",new Object[]{n,s.inspectionId});
            queueRow(d,"signature",s.id,"UPSERT",n);d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        CloudSyncScheduler.scheduleSoon(context);
    }
    public List<Signature> signatures(String iid){List<Signature>o=new ArrayList<>();try(Cursor c=raw().query("signatures",null,"inspection_id=? AND deleted_at IS NULL",new String[]{iid},null,null,"role")){while(c.moveToNext()){Signature s=new Signature();s.id=s(c,"id");s.inspectionId=iid;s.role=s(c,"role");s.path=s(c,"local_path");s.sha256=s(c,"sha256");o.add(s);}}return o;}
    private String ensureDeviceId(){String id=setting("device_id","");if(id==null||id.isBlank()){id=UUID.randomUUID().toString();putSetting("device_id",id);}return id;}
    public String setting(String key,String def){try(Cursor c=raw().query("app_settings",new String[]{"setting_value"},"setting_key=?",new String[]{key},null,null,null)){return c.moveToFirst()?c.getString(0):def;}}
    public void putSetting(String key,String value){raw().insertWithOnConflict("app_settings",null,LedgerDatabase.values("setting_key",key,"setting_value",value,"updated_at",System.currentTimeMillis()),SQLiteDatabase.CONFLICT_REPLACE);}
}
