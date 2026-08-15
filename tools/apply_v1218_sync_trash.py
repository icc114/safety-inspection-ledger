from pathlib import Path
import re


def rep(path, old, new, count=1):
    p=Path(path); t=p.read_text(encoding='utf-8')
    if old not in t: raise SystemExit(f'pattern not found {path}: {old[:100]!r}')
    p.write_text(t.replace(old,new,count),encoding='utf-8')

# Android version
rep('app/build.gradle', "versionCode 20\n        versionName '1.2.17'", "versionCode 21\n        versionName '1.2.18'")

# Friendly sync error formatter.
Path('app/src/main/java/cn/safetyledger/app/sync/SyncErrorFormatter.java').write_text(r'''package cn.safetyledger.app.sync;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Converts transport exceptions into messages that ordinary users can act on. */
public final class SyncErrorFormatter {
    private SyncErrorFormatter() {}

    public static boolean isNetwork(Throwable error) {
        for (Throwable current=error; current!=null; current=current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException
                    || current instanceof UnknownHostException || current instanceof NoRouteToHostException) return true;
            String m=current.getMessage(); if(m==null) continue; String s=m.toLowerCase();
            if(s.contains("failed to connect")||s.contains("timeout")||s.contains("timed out")
                    ||s.contains("unable to resolve host")||s.contains("network is unreachable")
                    ||s.contains("no route to host")) return true;
        }
        return false;
    }

    public static String format(Throwable error) {
        if (isNetwork(error)) return "网络连接问题：暂时无法连接云同步服务器。请检查 Wi‑Fi/移动网络、VPN/代理后重试；本机检查记录不会因此丢失，网络恢复后可继续同步。";
        String message=null;
        for(Throwable current=error;current!=null;current=current.getCause()) if(current.getMessage()!=null&&!current.getMessage().isBlank())message=current.getMessage();
        return message==null?error.getClass().getSimpleName():message;
    }

    public static String notificationTitle(Throwable error) {
        return isNetwork(error)?"安全检查台账同步失败 · 网络问题":"安全检查台账同步失败";
    }
}
''',encoding='utf-8')

# MediaService: safe automatic archive only releases untouched original duplicates.
p=Path('app/src/main/java/cn/safetyledger/app/media/MediaService.java');t=p.read_text(encoding='utf-8')
anchor='''    public void deleteInspectionMedia(String inspectionId) {\n'''
if anchor not in t: raise SystemExit('MediaService delete anchor not found')
method='''    /** Releases only untouched source-photo duplicates; watermarked business JPEGs remain. */
    public long releaseOriginalCopies(String inspectionId) {
        File directory = mediaDir(inspectionId); long released = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (!file.getName().endsWith("-original.bin")) continue;
            long size=file.length(); if(file.delete()) released += size;
        }
        return released;
    }

'''
t=t.replace(anchor,method+anchor,1);p.write_text(t,encoding='utf-8')

# Repository cloud tombstone timestamps and restore signal.
p=Path('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java');t=p.read_text(encoding='utf-8')
old='''    public void permanentDelete(String id){SQLiteDatabase d=raw();d.beginTransaction();try{d.delete("inspections","id=?",new String[]{id});tombstone("inspection",id);d.setTransactionSuccessful();}finally{d.endTransaction();}}\n'''
if old not in t: raise SystemExit('permanentDelete not found')
new=old+'''    public void permanentDeleteAt(String id,long deletedAt){SQLiteDatabase d=raw();d.beginTransaction();try{d.delete("inspections","id=?",new String[]{id});d.execSQL("INSERT OR REPLACE INTO tombstones(entity_type,entity_id,deleted_at,synced_at) VALUES('inspection',?,?,NULL)",new Object[]{id,deletedAt});d.setTransactionSuccessful();}finally{d.endTransaction();}}
    public void clearInspectionTombstone(String id){raw().delete("tombstones","entity_type='inspection' AND entity_id=?",new String[]{id});}
'''
t=t.replace(old,new,1);p.write_text(t,encoding='utf-8')

# BackupService: record-only media recovery package (database remains small/full for schema compatibility).
p=Path('app/src/main/java/cn/safetyledger/app/backup/BackupService.java');t=p.read_text(encoding='utf-8')
t=t.replace('exportInternal(destination,password,MAGIC,true);','exportInternal(destination,password,MAGIC,true,null);',1)
t=t.replace('exportInternal(destination,password,MAGIC,false);','exportInternal(destination,password,MAGIC,false,null);',1)
t=t.replace('exportInternal(destination,PORTABLE_KEY.clone(),PORTABLE_MAGIC,true);','exportInternal(destination,PORTABLE_KEY.clone(),PORTABLE_MAGIC,true,null);',1)
old='''    private void exportInternal(OutputStream destination,char[]password,byte[]magic,boolean includeOriginals)throws Exception{'''
new='''    public void exportInspectionRecovery(OutputStream destination,char[]password,String inspectionId)throws Exception{
        if(password.length<8)throw new IllegalArgumentException("密码至少 8 位");
        if(inspectionId==null||inspectionId.isBlank())throw new IllegalArgumentException("检查记录无效");
        exportInternal(destination,password,MAGIC,false,inspectionId);
    }
    private void exportInternal(OutputStream destination,char[]password,byte[]magic,boolean includeOriginals,String onlyInspectionId)throws Exception{'''
if old not in t: raise SystemExit('BackupService exportInternal signature not found')
t=t.replace(old,new,1)
old='''                File media=new File(context.getFilesDir(),"business_media");
                zipDir(z,media,"business_media/",includeOriginals);
'''
new='''                File media=new File(context.getFilesDir(),"business_media");
                if(onlyInspectionId==null) zipDir(z,media,"business_media/",includeOriginals);
                else zipDir(z,new File(media,onlyInspectionId),"business_media/"+onlyInspectionId+"/",includeOriginals);
'''
if old not in t: raise SystemExit('BackupService media zip block not found')
t=t.replace(old,new,1);p.write_text(t,encoding='utf-8')

# WebDavClient: lightweight cloud trash directory and metadata/bundle operations.
p=Path('app/src/main/java/cn/safetyledger/app/sync/WebDavClient.java');t=p.read_text(encoding='utf-8')
t=t.replace('        mkcol(adminRecoveryUrl(space));\n','        mkcol(adminRecoveryUrl(space));\n        mkcol(trashUrl(space));\n',1)
anchor='''    public void download(String space, String name, File target) throws Exception {'''
if anchor not in t: raise SystemExit('WebDav download anchor not found')
methods=r'''    public List<String> listTrashMetadata(String space) throws Exception {
        ResponseInfo response=execute("PROPFIND",trashUrl(space),PROPFIND,"1");
        if(!response.successDav())throw failure("无法读取云端回收站",response);
        return davNames(response.body,".trash.json");
    }

    public void uploadTrashMetadata(String space,String inspectionId,String json)throws Exception{
        putBytes(trashFileUrl(space,inspectionId+".trash.json"),json.getBytes(StandardCharsets.UTF_8));
    }
    public String downloadTrashMetadata(String space,String inspectionId)throws Exception{
        return new String(getBytes(trashFileUrl(space,inspectionId+".trash.json")),StandardCharsets.UTF_8);
    }
    public void uploadTrashRecovery(String space,String inspectionId,File source)throws Exception{
        RequestBody body=RequestBody.create(BINARY,source);
        try(Response response=http.newCall(request(trashFileUrl(space,inspectionId+".safetydata")).put(body).build()).execute()){
            if(!response.isSuccessful())throw failure("上传云端回收站恢复包失败",response);
        }
    }
    public void downloadTrashRecovery(String space,String inspectionId,File target)throws Exception{
        try(Response response=http.newCall(request(trashFileUrl(space,inspectionId+".safetydata")).get().build()).execute()){
            if(!response.isSuccessful())throw failure("下载云端回收站恢复包失败",response);
            ResponseBody body=response.body();if(body==null)throw new java.io.IOException("云端回收站恢复包为空");
            try(InputStream input=body.byteStream();FileOutputStream output=new FileOutputStream(target)){copy(input,output);}
        }
    }
    public void deleteTrashEntry(String space,String inspectionId)throws Exception{
        delete(trashFileUrl(space,inspectionId+".trash.json"));delete(trashFileUrl(space,inspectionId+".safetydata"));
    }

    private List<String> davNames(byte[] body,String suffix)throws Exception{
        String xmlText=new String(body,StandardCharsets.UTF_8);String upper=xmlText.toUpperCase(java.util.Locale.ROOT);
        if(upper.contains("<!DOCTYPE")||upper.contains("<!ENTITY"))throw new java.io.IOException("服务器返回了不安全的 XML DTD/ENTITY，已拒绝解析");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        setXmlFeatureSafely(factory,"http://apache.org/xml/features/disallow-doctype-decl",true);
        setXmlFeatureSafely(factory,"http://xml.org/sax/features/external-general-entities",false);
        setXmlFeatureSafely(factory,"http://xml.org/sax/features/external-parameter-entities",false);
        try{factory.setXIncludeAware(false);}catch(RuntimeException|AbstractMethodError ignored){}
        try{factory.setExpandEntityReferences(false);}catch(RuntimeException|AbstractMethodError ignored){}
        Document document=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(body));NodeList hrefs=document.getElementsByTagNameNS("*","href");
        List<String> names=new ArrayList<>();for(int i=0;i<hrefs.getLength();i++){String href=hrefs.item(i).getTextContent();int slash=href.lastIndexOf('/');String name=URLDecoder.decode(slash>=0?href.substring(slash+1):href,StandardCharsets.UTF_8.name());if(name.endsWith(suffix)&&!names.contains(name))names.add(name);}return names;
    }

'''
t=t.replace(anchor,methods+anchor,1)
# URL helpers are near the tail.
old='''    private String adminRecoveryUrl(String space) { return spaceUrl(space) + "admin-recovery/"; }'''
if old not in t: raise SystemExit('adminRecoveryUrl helper not found')
t=t.replace(old,old+'\n    private String trashUrl(String space) { return spaceUrl(space) + "trash/"; }',1)
old='''    private String recoveryFileUrl(String space, String name) { return adminRecoveryUrl(space) + segment(name); }'''
if old not in t: raise SystemExit('recoveryFileUrl helper not found')
t=t.replace(old,old+'\n    private String trashFileUrl(String space, String name) { return trashUrl(space) + segment(name); }',1)
p.write_text(t,encoding='utf-8')

# Scheduler: separate lightweight trash signal channel every 15 minutes.
p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncScheduler.java');t=p.read_text(encoding='utf-8')
t=t.replace('    public static final int DEVICE_JOB_ID = 1142028;','    public static final int DEVICE_JOB_ID = 1142028;\n    public static final int TRASH_JOB_ID = 1142029;',1)
t=t.replace('    private static final long DEVICE_INTERVAL = 30L * 60L * 1000L;','    private static final long DEVICE_INTERVAL = 30L * 60L * 1000L;\n    private static final long TRASH_INTERVAL = 15L * 60L * 1000L;',1)
old='''        scheduler.schedule(devices);
    }
'''
new='''        scheduler.schedule(devices);
        JobInfo trash = new JobInfo.Builder(TRASH_JOB_ID,
                new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TRASH_INTERVAL)
                .setPersisted(true)
                .build();
        scheduler.schedule(trash);
    }

    public static void scheduleTrashSoon(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler==null)return;
        JobInfo job=new JobInfo.Builder(TRASH_JOB_ID,new ComponentName(context,CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(2_000L).setOverrideDeadline(30_000L).build();
        scheduler.schedule(job);
    }
'''
if old not in t: raise SystemExit('scheduler devices block not found')
t=t.replace(old,new,1)
t=t.replace('        scheduler.cancel(DEVICE_JOB_ID);','        scheduler.cancel(DEVICE_JOB_ID);\n        scheduler.cancel(TRASH_JOB_ID);',1);p.write_text(t,encoding='utf-8')

# Job service: lightweight trash job + readable network notifications.
p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncJobService.java');t=p.read_text(encoding='utf-8')
t=t.replace('''            boolean deviceJob = params.getJobId() == CloudSyncScheduler.DEVICE_JOB_ID;
            try {
                CloudSyncService service = new CloudSyncService(this);
                if (deviceJob) service.syncDeviceManagement();
                else service.syncNow();
''','''            boolean deviceJob = params.getJobId() == CloudSyncScheduler.DEVICE_JOB_ID;
            boolean trashJob = params.getJobId() == CloudSyncScheduler.TRASH_JOB_ID;
            try {
                CloudSyncService service = new CloudSyncService(this);
                if (deviceJob) service.syncDeviceManagement();
                else if (trashJob) service.syncTrashSignals();
                else service.syncNow();
''',1)
t=t.replace('''                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
''','''                String message = SyncErrorFormatter.format(error);
''',1)
t=t.replace('''                    repo.putSetting(deviceJob ? "last_device_sync_error" : "last_sync_error", message);
                    if (!deviceJob) notifyFailure(message);
''','''                    repo.putSetting(deviceJob ? "last_device_sync_error" : trashJob ? "last_trash_sync_error" : "last_sync_error", message);
                    if (!deviceJob && !trashJob) notifyFailure(error, message);
''',1)
t=t.replace('''        }, params.getJobId() == CloudSyncScheduler.DEVICE_JOB_ID
                ? "safety-ledger-device-sync" : "safety-ledger-content-sync").start();
''','''        }, params.getJobId() == CloudSyncScheduler.DEVICE_JOB_ID ? "safety-ledger-device-sync"
                : params.getJobId() == CloudSyncScheduler.TRASH_JOB_ID ? "safety-ledger-trash-sync" : "safety-ledger-content-sync").start();
''',1)
t=t.replace('''    private void notifyFailure(String message) {''','''    private void notifyFailure(Throwable error, String message) {''',1)
t=t.replace('''                .setContentTitle("安全检查台账同步失败")''','''                .setContentTitle(SyncErrorFormatter.notificationTitle(error))''',1)
p.write_text(t,encoding='utf-8')

# CloudSyncService: lightweight trash events, 30-day expiry, detailed metadata, smaller recovery package, safe auto archive.
p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java');t=p.read_text(encoding='utf-8')
t=t.replace('    private static final AtomicBoolean DEVICE_RUNNING = new AtomicBoolean(false);','    private static final AtomicBoolean DEVICE_RUNNING = new AtomicBoolean(false);\n    private static final AtomicBoolean TRASH_RUNNING = new AtomicBoolean(false);\n    private static final long TRASH_RETENTION_MS = 30L*24L*60L*60L*1000L;',1)
# Apply trash signals before heavy content merge.
needle='''            WebDavClient client = client(config);
            prepare(client, config);

            String deviceId = ensureDeviceId();
'''
replacement='''            WebDavClient client = client(config);
            prepare(client, config);
            syncTrashSignalsInternal(client,config,false);

            String deviceId = ensureDeviceId();
'''
if needle not in t: raise SystemExit('syncNow prepare block not found')
t=t.replace(needle,replacement,1)
# Auto archive after successful upload.
t=t.replace('''            uploadSnapshot(backup, client, config, deviceId);

            long now = System.currentTimeMillis();
''','''            uploadSnapshot(backup, client, config, deviceId);
            runAutoArchiveAfterSuccessfulSync();

            long now = System.currentTimeMillis();
''',1)
# Replace delete/list/restore block.
start=t.index('    public DeleteResult archiveAndPermanentlyDelete(')
end=t.index('    private static boolean samePassword',start)
block=r'''    public TrashSyncResult syncTrashSignals() throws Exception {
        if(!TRASH_RUNNING.compareAndSet(false,true))throw new IllegalStateException("回收站状态同步正在运行，请稍后再试");
        Config config=null;try{config=requireConfig();WebDavClient client=client(config);prepare(client,config);return syncTrashSignalsInternal(client,config,true);}finally{if(config!=null)Arrays.fill(config.spacePassword,'\0');TRASH_RUNNING.set(false);}
    }

    private TrashSyncResult syncTrashSignalsInternal(WebDavClient client,Config config,boolean scheduleRestore)throws Exception{
        int deleted=0,restores=0,purged=0;long now=System.currentTimeMillis();
        for(String file:client.listTrashMetadata(config.space)){
            String id=file.substring(0,file.length()-".trash.json".length());JSONObject json;
            try{json=new JSONObject(client.downloadTrashMetadata(config.space,id));}catch(Exception malformed){continue;}
            long expires=json.optLong("expiresAt",0L);String state=json.optString("state","DELETED");
            if(expires>0&&now>=expires){
                String role=deviceRole(ensureDeviceId());if("OWNER".equals(role)||"ADMIN".equals(role)){client.deleteTrashEntry(config.space,id);purged++;}
                continue;
            }
            if("DELETED".equals(state)){
                long deletedAt=json.optLong("deletedAt",now);
                if(repo.inspection(id)!=null){new MediaService(context).deleteInspectionMedia(id);repo.permanentDeleteAt(id,deletedAt);deleted++;}
            }else if("RESTORED".equals(state)){
                repo.clearInspectionTombstone(id);restores++;
            }
        }
        repo.putSetting("last_trash_sync_at",String.valueOf(now));repo.putSetting("last_trash_sync_error","");
        if(scheduleRestore&&restores>0)CloudSyncScheduler.scheduleSoon(context);
        return new TrashSyncResult(deleted,restores,purged,now);
    }

    public DeleteResult archiveAndPermanentlyDelete(String inspectionId,char[] entered,ProgressListener listener)throws Exception{
        Config config=null;try{
            config=requireConfig();if(!samePassword(entered,config.spacePassword))throw new SecurityException("云同步空间密码错误");
            cn.safetyledger.app.data.Entities.Inspection inspection=repo.inspection(inspectionId);if(inspection==null)throw new IllegalArgumentException("检查记录不存在");
            WebDavClient client=client(config);prepare(client,config);String deviceId=ensureDeviceId();File recovery=File.createTempFile("safety-trash-",".safetydata",context.getCacheDir());
            try{
                progress(listener,"正在生成本条记录恢复包…");try(FileOutputStream output=new FileOutputStream(recovery)){new BackupService(context).exportInspectionRecovery(output,config.spacePassword.clone(),inspectionId);}
                long now=System.currentTimeMillis();long expires=now+TRASH_RETENTION_MS;
                JSONObject meta=new JSONObject();meta.put("version",2);meta.put("inspectionId",inspectionId);meta.put("templateName",inspection.templateName);meta.put("inspectionDate",inspection.date);meta.put("inspectionTime",inspection.time);meta.put("inspectionType",inspection.inspectionType);meta.put("location",inspection.location);meta.put("deletedAt",now);meta.put("expiresAt",expires);meta.put("deletedBy",deviceId);meta.put("state","DELETED");
                progress(listener,"正在写入云端回收站…");client.uploadTrashRecovery(config.space,inspectionId,recovery);client.uploadTrashMetadata(config.space,inspectionId,meta.toString());
                new MediaService(context).deleteInspectionMedia(inspectionId);repo.permanentDeleteAt(inspectionId,now);CloudSyncScheduler.scheduleTrashSoon(context);
                return new DeleteResult(inspectionId+".trash.json",now);
            }finally{recovery.delete();}
        }finally{if(entered!=null)Arrays.fill(entered,'\0');if(config!=null)Arrays.fill(config.spacePassword,'\0');}
    }

    public List<RecoveryEntry> listAdminRecovery()throws Exception{
        Config config=null;try{
            config=requireConfig();String current=ensureDeviceId();String role=deviceRole(current);if(!("OWNER".equals(role)||"ADMIN".equals(role)))throw new SecurityException("只有管理员设备可以查看云端回收站");
            WebDavClient client=client(config);prepare(client,config);syncTrashSignalsInternal(client,config,false);List<RecoveryEntry> out=new ArrayList<>();
            for(String file:client.listTrashMetadata(config.space)){
                String id=file.substring(0,file.length()-".trash.json".length());try{JSONObject j=new JSONObject(client.downloadTrashMetadata(config.space,id));if(!"DELETED".equals(j.optString("state","DELETED")))continue;out.add(new RecoveryEntry(file,id,j.optLong("deletedAt",0),j.optString("inspectionDate",""),j.optString("inspectionTime",""),j.optString("templateName","检查记录"),j.optString("location",""),j.optString("inspectionType",""),j.optLong("expiresAt",0),false));}catch(Exception ignored){}
            }
            for(String name:client.listAdminRecovery(config.space)){String base=name.substring(0,name.length()-".safetydata".length());String[] parts=base.split("__",3);if(parts.length<2)continue;long deletedAt=0;try{deletedAt=Long.parseLong(parts[1]);}catch(Exception ignored){}out.add(new RecoveryEntry(name,parts[0],deletedAt,"","","旧版恢复记录","","",0,true));}
            out.sort((a,b)->Long.compare(b.deletedAt(),a.deletedAt()));return out;
        }finally{if(config!=null)Arrays.fill(config.spacePassword,'\0');}
    }

    public RestoreResult restoreAdminRecovery(String recoveryName)throws Exception{
        Config config=null;try{
            config=requireConfig();String current=ensureDeviceId();String role=deviceRole(current);if(!("OWNER".equals(role)||"ADMIN".equals(role)))throw new SecurityException("只有管理员设备可以恢复已删除记录");
            WebDavClient client=client(config);prepare(client,config);BackupService backup=new BackupService(context);String inspectionId;File incoming=File.createTempFile("safety-trash-restore-",".safetydata",context.getCacheDir());
            try{
                if(recoveryName.endsWith(".trash.json")){
                    inspectionId=recoveryName.substring(0,recoveryName.length()-".trash.json".length());client.downloadTrashRecovery(config.space,inspectionId,incoming);
                }else{
                    String base=recoveryName.endsWith(".safetydata")?recoveryName.substring(0,recoveryName.length()-11):recoveryName;String[] parts=base.split("__",3);if(parts.length<2)throw new IllegalArgumentException("恢复文件名称无效");inspectionId=parts[0];client.downloadAdminRecovery(config.space,recoveryName,incoming);
                }
                try(FileInputStream input=new FileInputStream(incoming)){BackupService.RestorePackage restore=backup.decryptAndValidate(input,config.spacePassword.clone());backup.restoreInspection(restore,inspectionId);}
                repo.clearInspectionTombstoneAndRestore(inspectionId);long now=System.currentTimeMillis();
                if(recoveryName.endsWith(".trash.json")){
                    JSONObject meta=new JSONObject(client.downloadTrashMetadata(config.space,inspectionId));meta.put("state","RESTORED");meta.put("restoredAt",now);meta.put("restoredBy",current);meta.put("expiresAt",Math.max(meta.optLong("expiresAt",0),now+7L*24L*60L*60L*1000L));client.uploadTrashMetadata(config.space,inspectionId,meta.toString());
                }
                uploadSnapshot(backup,client,config,current);CloudSyncScheduler.scheduleTrashSoon(context);return new RestoreResult(inspectionId,now);
            }finally{incoming.delete();}
        }finally{if(config!=null)Arrays.fill(config.spacePassword,'\0');}
    }

    private void runAutoArchiveAfterSuccessfulSync(){
        if(!"1".equals(repo.setting("auto_archive_enabled","0")))return;
        String cutoff=java.time.LocalDate.now().minusMonths(6).toString();long released=0;int records=0;
        try(Cursor c=repo.raw().rawQuery("SELECT id FROM inspections WHERE deleted_at IS NULL AND inspection_date<? AND status IN ('RECTIFIED','COMPLETED')",new String[]{cutoff})){
            MediaService media=new MediaService(context);while(c.moveToNext()){long bytes=media.releaseOriginalCopies(c.getString(0));if(bytes>0){released+=bytes;records++;}}
        }
        repo.putSetting("last_auto_archive_at",String.valueOf(System.currentTimeMillis()));repo.putSetting("last_auto_archive_records",String.valueOf(records));repo.putSetting("last_auto_archive_bytes",String.valueOf(released));
    }

'''
t=t[:start]+block+t[end:]
# records at bottom
old='''    public record RecoveryEntry(String name,String inspectionId,long deletedAt) {}'''
if old not in t: raise SystemExit('RecoveryEntry record not found')
t=t.replace(old,'    public record RecoveryEntry(String name,String inspectionId,long deletedAt,String date,String time,String templateName,String location,String inspectionType,long expiresAt,boolean legacy) {}\n    public record TrashSyncResult(int deleted,int restored,int purged,long completedAt) {}',1)
p.write_text(t,encoding='utf-8')

# Settings: remove local delete password, add functional safe auto archive, friendly network messages.
p=Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java');t=p.read_text(encoding='utf-8')
t=t.replace('import android.widget.Spinner;','import android.widget.Spinner;\nimport android.widget.Switch;',1)
t=t.replace('import cn.safetyledger.app.security.PasswordHash;\n','')
t=t.replace('import cn.safetyledger.app.sync.WebDavClient;','import cn.safetyledger.app.sync.WebDavClient;\nimport cn.safetyledger.app.sync.SyncErrorFormatter;',1)
t=t.replace('"回收站", "恢复误删记录或使用密码永久删除"','"回收站", "本机恢复、云端30天回收站和管理员恢复"',1)
# security card whole method by regex.
pattern=r'    private LinearLayout securityCard\(\) \{.*?\n    \}\n\n    private LinearLayout menuRow'
m=re.search(pattern,t,re.S)
if not m: raise SystemExit('securityCard method not found')
new_method=r'''    private LinearLayout securityCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "5", "安全与本地存储", null));
        TextView deletion = Ui.text(this,
                "永久删除不再设置额外的本机密码。启用云同步后，从回收站永久删除时统一验证“云同步空间密码”；云端回收站保留 30 天，管理员可在期限内恢复。",
                13, false);
        deletion.setTextColor(Ui.MUTED); card.addView(deletion); card.addView(Ui.gap(this,8));
        Switch archive = new Switch(this);
        archive.setText("超过 6 个月自动归档（仅清理未处理原图副本）");
        archive.setTextSize(14); archive.setChecked("1".equals(repo.setting("auto_archive_enabled","0")));
        archive.setOnCheckedChangeListener((button,checked)->{
            repo.putSetting("auto_archive_enabled",checked?"1":"0");
            Ui.toast(this,checked?"自动归档已开启":"自动归档已关闭");
        });
        card.addView(archive);
        TextView note = Ui.text(this,
                "开启后，仅在一次云同步成功之后处理：对超过 6 个月且已完成/已整改的记录，删除手机中重复保存的“未处理原始照片副本”；检查记录、水印照片、整改/复查照片和签名全部保留，不会自动删除检查记录。",
                12, false);
        note.setTextColor(Ui.MUTED);card.addView(note);
        return card;
    }

    private LinearLayout menuRow'''
t=t[:m.start()]+new_method+t[m.end():]
# remove setDeletePassword method.
t=re.sub(r'\n    private void setDeletePassword\(\) \{.*?\n    \}\n\n    private void saveAndEnable', '\n    private void saveAndEnable', t, flags=re.S)
# no longer derive extra deletion hash.
t=re.sub(r'\n\s*repo\.putSetting\("delete_password_hash",\s*PasswordHash\.create\(spacePassword\.toCharArray\(\)\)\);','',t)
# Friendly readable error.
pattern=r'    private String readableError\(Throwable error\) \{.*?\n    \}'
t=re.sub(pattern,'    private String readableError(Throwable error) { return SyncErrorFormatter.format(error); }',t,flags=re.S)
# Notification title classify from message.
t=t.replace('.setContentTitle("安全检查台账同步失败")','.setContentTitle(message.startsWith("网络连接问题：") ? "安全检查台账同步失败 · 网络问题" : "安全检查台账同步失败")',1)
p.write_text(t,encoding='utf-8')

# TrashActivity full rewrite.
Path('app/src/main/java/cn/safetyledger/app/TrashActivity.java').write_text(r'''package cn.safetyledger.app;

import android.app.Activity;import android.app.AlertDialog;import android.os.Bundle;import android.text.InputType;import android.text.method.PasswordTransformationMethod;import android.widget.*;
import cn.safetyledger.app.data.Entities.Inspection;import cn.safetyledger.app.data.LedgerRepository;import cn.safetyledger.app.media.MediaService;import cn.safetyledger.app.sync.CloudSyncService;import cn.safetyledger.app.sync.SyncErrorFormatter;
import java.text.DateFormat;import java.util.*;

public final class TrashActivity extends Activity{
    private LedgerRepository repo;private LinearLayout list;private CloudSyncService cloud;
    @Override protected void onCreate(Bundle state){super.onCreate(state);Ui.setupWindow(this);repo=new LedgerRepository(this);cloud=new CloudSyncService(this);LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);root.addView(Ui.appBar(this,"回收站"));ScrollView scroll=new ScrollView(this);list=Ui.column(this);list.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,24));scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);load();}
    private void load(){list.removeAllViews();TextView note=Ui.text(this,"普通删除只进入本机回收站，可直接恢复。启用云同步后，永久删除统一验证云同步空间密码，并写入轻量云端回收站指令；其他设备无需等待整包照片同步即可处理删除。云端回收站保留30天。",13,false);note.setTextColor(Ui.MUTED);list.addView(note);list.addView(Ui.gap(this,8));if(isAdmin()&&cloud.hasConfiguredSync()){Button recovery=Ui.secondaryButton(this,"云端回收站 · 管理员查看和恢复");recovery.setOnClickListener(v->loadAdminRecovery());list.addView(recovery);list.addView(Ui.gap(this,10));}int count=0;for(Inspection inspection:repo.list(null,null,null,true,1,100000).rows){count++;LinearLayout card=Ui.card(this);card.setPadding(Ui.dp(this,12),Ui.dp(this,9),Ui.dp(this,12),Ui.dp(this,9));card.addView(Ui.text(this,inspection.date+" "+inspection.time+" · "+inspection.templateName+"\n"+inspection.location,15,true));LinearLayout actions=Ui.row(this);Button restore=Ui.compactButton(this,"恢复记录",true);Button delete=Ui.dangerButton(this,"永久删除");restore.setOnClickListener(v->{repo.restore(inspection.id);Ui.toast(this,"记录已恢复");load();});delete.setOnClickListener(v->confirm(inspection));actions.addView(restore,Ui.weight(1));actions.addView(Ui.horizontalGap(this,6));actions.addView(delete,Ui.weight(1));card.addView(actions);list.addView(card);list.addView(Ui.gap(this,8));}if(count==0){TextView empty=Ui.text(this,"本机回收站为空",16,true);empty.setTextColor(Ui.MUTED);empty.setGravity(android.view.Gravity.CENTER);list.addView(empty);}}
    private boolean isAdmin(){String r=repo.setting("cloud_role","");return "OWNER".equals(r)||"ADMIN".equals(r)||"PRIMARY".equals(repo.setting("device_role",""));}
    private void confirm(Inspection inspection){if(!cloud.hasConfiguredSync()){new AlertDialog.Builder(this).setTitle("仅永久删除本机记录？").setMessage("当前没有启用云同步，因此不会建立云端30天恢复副本，也不会通知其他设备。确定只永久删除本机这条记录吗？").setPositiveButton("仅删除本机",(d,w)->{new MediaService(this).deleteInspectionMedia(inspection.id);repo.permanentDelete(inspection.id);Ui.toast(this,"本机记录已永久删除");load();}).setNegativeButton("取消",null).show();return;}EditText password=Ui.input(this,"请输入云同步空间密码");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setTransformationMethod(PasswordTransformationMethod.getInstance());new AlertDialog.Builder(this).setTitle("移入云端回收站").setMessage("密码验证成功后，本条记录会进入云端回收站并保留30天。其他设备接收到轻量删除指令后会自动删除本地对应记录；管理员30天内仍可恢复。").setView(password).setPositiveButton("确认永久删除",(d,w)->deleteWithCloudRecovery(inspection,password.getText().toString().toCharArray())).setNegativeButton("取消",null).show();}
    private void deleteWithCloudRecovery(Inspection inspection,char[] password){Ui.toast(this,"正在验证密码并写入云端回收站…");new Thread(()->{try{cloud.archiveAndPermanentlyDelete(inspection.id,password,null);runOnUiThread(()->{Ui.toast(this,"已删除；云端回收站保留30天");load();});}catch(Exception error){String msg=SyncErrorFormatter.format(error);runOnUiThread(()->new AlertDialog.Builder(this).setTitle(SyncErrorFormatter.isNetwork(error)?"网络连接问题":"删除失败").setMessage(msg).setPositiveButton("确定",null).show());}},"trash-cloud-delete").start();}
    private void loadAdminRecovery(){Ui.toast(this,"正在读取云端回收站…");new Thread(()->{try{List<CloudSyncService.RecoveryEntry> entries=cloud.listAdminRecovery();runOnUiThread(()->showRecovery(entries));}catch(Exception error){String msg=SyncErrorFormatter.format(error);runOnUiThread(()->new AlertDialog.Builder(this).setTitle(SyncErrorFormatter.isNetwork(error)?"网络连接问题":"读取失败").setMessage(msg).setPositiveButton("确定",null).show());}},"admin-recovery-list").start();}
    private void showRecovery(List<CloudSyncService.RecoveryEntry> entries){if(entries.isEmpty()){Ui.toast(this,"云端回收站为空");return;}LinearLayout box=Ui.column(this);for(CloudSyncService.RecoveryEntry e:entries){String when=e.deletedAt()>0?DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(e.deletedAt())):"未知时间";String expire=e.expiresAt()>0?"\n自动清空："+DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(e.expiresAt())):"";String title=(e.date().isBlank()?"":e.date()+" "+e.time()+" · ")+e.templateName()+"\n"+(e.location().isBlank()?"地点未知":e.location())+"\n删除时间："+when+expire;Button b=Ui.secondaryButton(this,title);b.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);b.setOnClickListener(v->confirmRecovery(e));box.addView(b);box.addView(Ui.gap(this,6));}ScrollView s=new ScrollView(this);s.addView(box);s.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1,Ui.dp(this,460)));new AlertDialog.Builder(this).setTitle("云端回收站").setView(s).setNegativeButton("关闭",null).show();}
    private void confirmRecovery(CloudSyncService.RecoveryEntry entry){String detail=(entry.date().isBlank()?"":entry.date()+" "+entry.time()+"\n")+entry.templateName()+"\n"+(entry.location().isBlank()?"地点未知":entry.location())+"\n\n恢复后，管理员设备会立即重新发布该检查记录；其他设备收到“恢复指令”后会自动安排检查内容同步。";new AlertDialog.Builder(this).setTitle("恢复这条检查记录？").setMessage(detail).setPositiveButton("恢复",(d,w)->new Thread(()->{try{cloud.restoreAdminRecovery(entry.name());runOnUiThread(()->{Ui.toast(this,"已恢复并发布到云端");load();});}catch(Exception error){String msg=SyncErrorFormatter.format(error);runOnUiThread(()->new AlertDialog.Builder(this).setTitle(SyncErrorFormatter.isNetwork(error)?"网络连接问题":"恢复失败").setMessage(msg).setPositiveButton("确定",null).show());}},"admin-recovery-restore").start()).setNegativeButton("取消",null).show();}
}
''',encoding='utf-8')

# PC 0.1.2 and force Word layout refresh for unmodified docs.
p=Path('desktop/pom.xml');t=p.read_text(encoding='utf-8').replace('<version>0.1.1</version>','<version>0.1.2</version>',1).replace('safety-ledger-pc-0.1.1-all','safety-ledger-pc-0.1.2-all');p.write_text(t,encoding='utf-8')
p=Path('.github/workflows/windows-pc-build.yml');t=p.read_text(encoding='utf-8').replace('0.1.1','0.1.2');p.write_text(t,encoding='utf-8')
p=Path('desktop/src/main/java/cn/safetyledger/pc/ArchiveService.java');t=p.read_text(encoding='utf-8')
old='''        Path main=folder.resolve("检查记录.docx"),hash=folder.resolve(".system-docx.sha256");Path output=main;
        if(Files.isRegularFile(main)&&Files.isRegularFile(hash)){
            String expected=Files.readString(hash).trim();String actual=DataPackageCodec.sha256(main);
            if(!actual.equalsIgnoreCase(expected))output=folder.resolve("检查记录-系统更新-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".docx");
        }
        WordExporter.write(r,output);String digest=DataPackageCodec.sha256(output);if(output.equals(main))Files.writeString(hash,digest,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
'''
new='''        Path main=folder.resolve("检查记录.docx"),hash=folder.resolve(".system-docx.sha256"),layout=folder.resolve(".word-layout-version");Path output=main;
        boolean layoutOld=!Files.isRegularFile(layout)||!String.valueOf(WordExporter.LAYOUT_VERSION).equals(Files.readString(layout).trim());
        if(Files.isRegularFile(main)&&Files.isRegularFile(hash)){
            String expected=Files.readString(hash).trim();String actual=DataPackageCodec.sha256(main);
            if(!actual.equalsIgnoreCase(expected))output=folder.resolve("检查记录-系统更新-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".docx");
            else if(layoutOld) output=main;
        }
        WordExporter.write(r,output);String digest=DataPackageCodec.sha256(output);if(output.equals(main)){Files.writeString(hash,digest,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);Files.writeString(layout,String.valueOf(WordExporter.LAYOUT_VERSION),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);}
'''
if old not in t: raise SystemExit('ArchiveService doc block not found')
t=t.replace(old,new,1);p.write_text(t,encoding='utf-8')

print('Applied Android 1.2.18 + PC 0.1.2 patch')
