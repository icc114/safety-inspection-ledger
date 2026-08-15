from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:120]!r}')
    text = text.replace(old, new, count)
    p.write_text(text, encoding='utf-8')

# Version
replace('app/build.gradle', "versionCode 18\n        versionName '1.2.15'", "versionCode 19\n        versionName '1.2.16'")

# Remove the redundant per-record PDF export from the record detail screen.
p = Path('app/src/main/java/cn/safetyledger/app/RecordDetailActivity.java')
t = p.read_text(encoding='utf-8')
t = t.replace('import cn.safetyledger.app.pdf.PdfExporter;\n', '')
t = t.replace('import java.io.OutputStream;\n', '')
t = t.replace('    private static final int PDF = 611;\n', '')
old = '''        content.addView(Ui.gap(this, 14));
        Button pdf = Ui.button(this, "导出本条 A4 PDF");
        pdf.setOnClickListener(view -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, "安全检查记录-" + model.date + ".pdf"), PDF));
        content.addView(pdf);
        content.addView(Ui.gap(this, 8));
        Button delete = Ui.secondaryButton(this, "移入回收站");
'''
new = '''        content.addView(Ui.gap(this, 14));
        Button delete = Ui.secondaryButton(this, "移入回收站");
'''
if old not in t: raise SystemExit('record detail PDF block not found')
t = t.replace(old, new, 1)
old = '''            } else if (request == PDF && data != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    new PdfExporter(this).export(List.of(repo.inspection(model.id)), output);
                }
                Ui.toast(this, "PDF 已导出");
            }
'''
if old not in t: raise SystemExit('record detail PDF result block not found')
t = t.replace(old, '            }\n', 1)
p.write_text(t, encoding='utf-8')

# Repository: admin recovery wins over an older tombstone.
p = Path('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java')
t = p.read_text(encoding='utf-8')
anchor = '    public void permanentDelete(String id){SQLiteDatabase d=raw();d.beginTransaction();try{d.delete("inspections","id=?",new String[]{id});tombstone("inspection",id);d.setTransactionSuccessful();}finally{d.endTransaction();}}\n'
if anchor not in t: raise SystemExit('permanentDelete anchor not found')
addition = anchor + '''    public void clearInspectionTombstoneAndRestore(String id){
        long now=System.currentTimeMillis();SQLiteDatabase d=raw();d.beginTransaction();
        try{
            d.delete("tombstones","entity_type='inspection' AND entity_id=?",new String[]{id});
            d.update("inspections",LedgerDatabase.values("deleted_at",null,"updated_at",now,"revision",1),"id=?",new String[]{id});
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        queue("inspection",id,"UPSERT");
    }
'''
t = t.replace(anchor, addition, 1)
p.write_text(t, encoding='utf-8')

# BackupService: restore one inspection out of an encrypted admin-recovery snapshot.
p = Path('app/src/main/java/cn/safetyledger/app/backup/BackupService.java')
t = p.read_text(encoding='utf-8')
anchor = '    private void normalizeMediaPaths(SQLiteDatabase d){'
if anchor not in t: raise SystemExit('BackupService normalize anchor not found')
method = '''    public void restoreInspection(RestorePackage p,String inspectionId)throws Exception{
        LedgerDatabase h=((SafetyLedgerApp)context).db();SQLiteDatabase d=h.getWritableDatabase();
        String path=p.database.getAbsolutePath().replace("'","''");
        d.execSQL("ATTACH DATABASE '"+path+"' AS incoming");d.beginTransaction();
        try{
            try(Cursor c=d.rawQuery("SELECT 1 FROM incoming.inspections WHERE id=? LIMIT 1",new String[]{inspectionId})){
                if(!c.moveToFirst())throw new IOException("恢复包中找不到该检查记录");
            }
            d.delete("inspections","id=?",new String[]{inspectionId});
            d.execSQL("INSERT INTO main.inspections SELECT * FROM incoming.inspections WHERE id=?",new Object[]{inspectionId});
            for(String table:new String[]{"inspection_items","media","signatures"}){
                if(tableExists(d,"incoming",table))d.execSQL("INSERT OR REPLACE INTO main."+table+" SELECT * FROM incoming."+table+" WHERE inspection_id=?",new Object[]{inspectionId});
            }
            d.setTransactionSuccessful();
        }finally{d.endTransaction();d.execSQL("DETACH DATABASE incoming");}
        File source=new File(new File(p.root,"business_media"),inspectionId);
        File target=new File(new File(context.getFilesDir(),"business_media"),inspectionId);
        deleteTree(target);copyMedia(source,target);normalizeMediaPaths(d);p.close();
    }

'''
t = t.replace(anchor, method + anchor, 1)
p.write_text(t, encoding='utf-8')

# WebDAV: dedicated encrypted admin-recovery vault.
p = Path('app/src/main/java/cn/safetyledger/app/sync/WebDavClient.java')
t = p.read_text(encoding='utf-8')
t = t.replace('        mkcol(deviceControlUrl(space));\n', '        mkcol(deviceControlUrl(space));\n        mkcol(adminRecoveryUrl(space));\n', 1)
anchor = '    public void download(String space, String name, File target) throws Exception {'
if anchor not in t: raise SystemExit('WebDav download anchor not found')
methods = '''    /** Encrypted snapshots retained only for administrator recovery after a permanent delete. */
    public List<String> listAdminRecovery(String space) throws Exception {
        ResponseInfo response = execute("PROPFIND", adminRecoveryUrl(space), PROPFIND, "1");
        if (!response.successDav()) throw failure("无法读取管理员恢复库", response);
        String xmlText = new String(response.body, StandardCharsets.UTF_8);
        String upperXml = xmlText.toUpperCase(java.util.Locale.ROOT);
        if (upperXml.contains("<!DOCTYPE") || upperXml.contains("<!ENTITY")) {
            throw new java.io.IOException("服务器返回了不安全的 XML DTD/ENTITY，已拒绝解析");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        setXmlFeatureSafely(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setXmlFeatureSafely(factory, "http://xml.org/sax/features/external-general-entities", false);
        setXmlFeatureSafely(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        try { factory.setXIncludeAware(false); } catch (RuntimeException | AbstractMethodError ignored) {}
        try { factory.setExpandEntityReferences(false); } catch (RuntimeException | AbstractMethodError ignored) {}
        Document document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(response.body));
        NodeList hrefs = document.getElementsByTagNameNS("*", "href");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < hrefs.getLength(); i++) {
            String href = hrefs.item(i).getTextContent();int slash = href.lastIndexOf('/');
            String name = URLDecoder.decode(slash >= 0 ? href.substring(slash + 1) : href, StandardCharsets.UTF_8.name());
            if (name.endsWith(".safetydata") && !names.contains(name)) names.add(name);
        }
        return names;
    }

    public void uploadAdminRecovery(String space,String name,File source)throws Exception{
        RequestBody body=RequestBody.create(BINARY,source);
        try(Response response=http.newCall(request(recoveryFileUrl(space,name)).put(body).build()).execute()){
            if(!response.isSuccessful())throw failure("上传管理员恢复副本失败",response);
        }
    }

    public void downloadAdminRecovery(String space,String name,File target)throws Exception{
        Request request=request(recoveryFileUrl(space,name)).get().build();
        try(Response response=http.newCall(request).execute()){
            if(!response.isSuccessful())throw failure("下载管理员恢复副本失败",response);
            ResponseBody body=response.body();if(body==null)throw new java.io.IOException("云端返回空恢复文件");
            try(InputStream input=body.byteStream();FileOutputStream output=new FileOutputStream(target)){copy(input,output);}
        }
    }

'''
t = t.replace(anchor, methods + anchor, 1)
t = t.replace('    private String deviceControlUrl(String space) { return spaceUrl(space) + "device-control/"; }\n',
'''    private String deviceControlUrl(String space) { return spaceUrl(space) + "device-control/"; }
    private String adminRecoveryUrl(String space) { return spaceUrl(space) + "admin-recovery/"; }
''', 1)
t = t.replace('    private String controlFileUrl(String space, String name) { return deviceControlUrl(space) + segment(name); }\n',
'''    private String controlFileUrl(String space, String name) { return deviceControlUrl(space) + segment(name); }
    private String recoveryFileUrl(String space, String name) { return adminRecoveryUrl(space) + segment(name); }
''', 1)
p.write_text(t, encoding='utf-8')

# Cloud sync service: protected permanent deletion + admin recovery + tombstone timestamp conflict rule.
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
t = p.read_text(encoding='utf-8')
anchor = '    private Config requireConfig() throws Exception {'
if anchor not in t: raise SystemExit('CloudSyncService requireConfig anchor not found')
methods = '''    public boolean hasConfiguredSync() {
        try { return loadConfig() != null; } catch (Exception ignored) { return false; }
    }

    public DeleteResult archiveAndPermanentlyDelete(String inspectionId,char[] entered,ProgressListener listener)throws Exception{
        Config config=null;
        try{
            config=requireConfig();
            if(!samePassword(entered,config.spacePassword))throw new SecurityException("云同步空间密码错误");
            Inspection existing=repo.inspection(inspectionId);if(existing==null)throw new IllegalArgumentException("检查记录不存在");
            WebDavClient client=client(config);prepare(client,config);String deviceId=ensureDeviceId();
            File recovery=File.createTempFile("safety-admin-recovery-",".safetydata",context.getCacheDir());
            try{
                progress(listener,"正在建立管理员恢复副本…");
                try(FileOutputStream output=new FileOutputStream(recovery)){
                    new BackupService(context).exportCloudSnapshot(output,config.spacePassword.clone());
                }
                long now=System.currentTimeMillis();
                String name=inspectionId+"__"+now+"__"+deviceId+".safetydata";
                client.uploadAdminRecovery(config.space,name,recovery);
                progress(listener,"恢复副本已保存，正在彻底删除本机记录…");
                new MediaService(context).deleteInspectionMedia(inspectionId);repo.permanentDelete(inspectionId);
                return new DeleteResult(name,now);
            }finally{recovery.delete();}
        }finally{
            if(entered!=null)Arrays.fill(entered,'\\0');
            if(config!=null)Arrays.fill(config.spacePassword,'\\0');
        }
    }

    public List<RecoveryEntry> listAdminRecovery()throws Exception{
        Config config=null;
        try{
            config=requireConfig();String current=ensureDeviceId();String role=deviceRole(current);
            if(!("OWNER".equals(role)||"ADMIN".equals(role)))throw new SecurityException("只有管理员设备可以查看恢复库");
            WebDavClient client=client(config);prepare(client,config);List<RecoveryEntry> out=new ArrayList<>();
            for(String name:client.listAdminRecovery(config.space)){
                String base=name.substring(0,name.length()-".safetydata".length());String[] parts=base.split("__",3);
                if(parts.length<2)continue;long deleted=0;try{deleted=Long.parseLong(parts[1]);}catch(Exception ignored){}
                out.add(new RecoveryEntry(name,parts[0],deleted));
            }
            out.sort((a,b)->Long.compare(b.deletedAt(),a.deletedAt()));return out;
        }finally{if(config!=null)Arrays.fill(config.spacePassword,'\\0');}
    }

    public RestoreResult restoreAdminRecovery(String recoveryName)throws Exception{
        Config config=null;
        try{
            config=requireConfig();String current=ensureDeviceId();String role=deviceRole(current);
            if(!("OWNER".equals(role)||"ADMIN".equals(role)))throw new SecurityException("只有管理员设备可以恢复已删除记录");
            String base=recoveryName.endsWith(".safetydata")?recoveryName.substring(0,recoveryName.length()-11):recoveryName;
            String[] parts=base.split("__",3);if(parts.length<2)throw new IllegalArgumentException("恢复文件名称无效");
            String inspectionId=parts[0];WebDavClient client=client(config);prepare(client,config);
            File incoming=File.createTempFile("safety-admin-restore-",".safetydata",context.getCacheDir());
            try{
                client.downloadAdminRecovery(config.space,recoveryName,incoming);BackupService backup=new BackupService(context);
                try(FileInputStream input=new FileInputStream(incoming)){
                    BackupService.RestorePackage restore=backup.decryptAndValidate(input,config.spacePassword.clone());
                    backup.restoreInspection(restore,inspectionId);
                }
                repo.clearInspectionTombstoneAndRestore(inspectionId);return new RestoreResult(inspectionId,System.currentTimeMillis());
            }finally{incoming.delete();}
        }finally{if(config!=null)Arrays.fill(config.spacePassword,'\\0');}
    }

    private static boolean samePassword(char[] a,char[] b){
        if(a==null||b==null)return false;int diff=a.length^b.length;int n=Math.max(a.length,b.length);
        for(int i=0;i<n;i++){char ca=i<a.length?a[i]:0;char cb=i<b.length?b[i]:0;diff|=ca^cb;}return diff==0;
    }

'''
t = t.replace(anchor, methods + anchor, 1)
start = t.index('    private void applyTombstones() {')
end = t.index('    private static void progress', start)
replacement = '''    private void applyTombstones() {
        List<Object[]> removals = new ArrayList<>();
        try (Cursor cursor = repo.raw().rawQuery("SELECT entity_type,entity_id,deleted_at FROM tombstones", null)) {
            while (cursor.moveToNext()) removals.add(new Object[]{cursor.getString(0), cursor.getString(1), cursor.getLong(2)});
        }
        SQLiteDatabase database = repo.raw();
        for (Object[] value : removals) {
            String type=(String)value[0],id=(String)value[1];long deletedAt=(Long)value[2];
            if ("inspection".equals(type)) {
                long updated=0;try(Cursor c=database.rawQuery("SELECT updated_at FROM inspections WHERE id=?",new String[]{id})){
                    if(c.moveToFirst())updated=c.getLong(0);
                }
                // Administrator recovery creates a newer inspection revision. Older tombstones must
                // not delete that restored record when stale peer snapshots are merged again.
                if(updated>deletedAt){database.delete("tombstones","entity_type='inspection' AND entity_id=?",new String[]{id});continue;}
                new MediaService(context).deleteInspectionMedia(id);database.delete("inspections","id=?",new String[]{id});
            } else if ("template".equals(type)) database.delete("templates", "id=?", new String[]{id});
            else if ("template_item".equals(type)) database.delete("template_items", "id=?", new String[]{id});
        }
    }

'''
t = t[:start] + replacement + t[end:]
record_anchor = '    public record ResetResult(int deletedSnapshots, String ownerDeviceId, long completedAt) {}\n'
if record_anchor not in t: raise SystemExit('result record anchor not found')
t = t.replace(record_anchor, record_anchor + '''    public record DeleteResult(String recoveryName,long completedAt) {}
    public record RecoveryEntry(String name,String inspectionId,long deletedAt) {}
    public record RestoreResult(String inspectionId,long completedAt) {}
''', 1)
p.write_text(t, encoding='utf-8')

# Trash screen rewritten for cloud-password protected deletion and admin recovery.
Path('app/src/main/java/cn/safetyledger/app/TrashActivity.java').write_text(r'''package cn.safetyledger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.safetyledger.app.data.Entities.Inspection;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;
import cn.safetyledger.app.security.PasswordHash;
import cn.safetyledger.app.sync.CloudSyncService;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public final class TrashActivity extends Activity {
    private LedgerRepository repo;
    private LinearLayout list;
    private CloudSyncService cloud;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);Ui.setupWindow(this);repo=new LedgerRepository(this);cloud=new CloudSyncService(this);
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);root.addView(Ui.appBar(this,"回收站"));
        ScrollView scroll=new ScrollView(this);list=Ui.column(this);list.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,24));
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);load();
    }

    private void load(){
        list.removeAllViews();TextView note=Ui.text(this,
                "普通删除只进入本机回收站，可直接恢复。启用云同步后，永久删除必须再次输入云同步空间密码；系统会先在云端建立管理员恢复副本，再向普通设备同步删除标记。",
                13,false);note.setTextColor(Ui.MUTED);list.addView(note);list.addView(Ui.gap(this,8));
        if(isAdmin()&&cloud.hasConfiguredSync()){
            Button recovery=Ui.secondaryButton(this,"管理员恢复库 · 找回已彻底删除记录");
            recovery.setOnClickListener(v->loadAdminRecovery());list.addView(recovery);list.addView(Ui.gap(this,10));
        }
        int count=0;for(Inspection inspection:repo.list(null,null,null,true,1,100000).rows){
            count++;LinearLayout card=Ui.card(this);card.setPadding(Ui.dp(this,12),Ui.dp(this,9),Ui.dp(this,12),Ui.dp(this,9));
            card.addView(Ui.text(this,inspection.date+" · "+inspection.templateName+"\n"+inspection.location,15,true));
            LinearLayout actions=Ui.row(this);Button restore=Ui.compactButton(this,"恢复记录",true);Button delete=Ui.dangerButton(this,"永久删除");
            restore.setOnClickListener(v->{repo.restore(inspection.id);Ui.toast(this,"记录已恢复");load();});
            delete.setOnClickListener(v->confirm(inspection));actions.addView(restore,Ui.weight(1));actions.addView(Ui.horizontalGap(this,6));actions.addView(delete,Ui.weight(1));
            card.addView(actions);list.addView(card);list.addView(Ui.gap(this,8));
        }
        if(count==0){TextView empty=Ui.text(this,"本机回收站为空",16,true);empty.setTextColor(Ui.MUTED);empty.setGravity(android.view.Gravity.CENTER);list.addView(empty);}
    }

    private boolean isAdmin(){String r=repo.setting("cloud_role","");return "OWNER".equals(r)||"ADMIN".equals(r)||"PRIMARY".equals(repo.setting("device_role",""));}

    private void confirm(Inspection inspection){
        boolean cloudEnabled=cloud.hasConfiguredSync();String hash=repo.setting("delete_password_hash","");
        if(!cloudEnabled&&hash.isBlank()){Ui.toast(this,"未启用云同步；请先在基础设置中设置本机永久删除密码");return;}
        EditText password=Ui.input(this,cloudEnabled?"请输入云同步空间密码":"请输入本机永久删除密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        new AlertDialog.Builder(this).setTitle(cloudEnabled?"云端永久删除确认":"本机永久删除确认")
                .setMessage(cloudEnabled?"系统会先保存一份仅管理员可恢复的加密云端副本，然后删除本机记录和照片，并把删除标记同步到其他普通设备。":"仅删除本机数据，不会产生云端恢复副本。")
                .setView(password).setPositiveButton("永久删除",(dialog,which)->{
                    char[] entered=password.getText().toString().toCharArray();
                    if(cloudEnabled)deleteWithCloudRecovery(inspection,entered);
                    else{
                        try{if(!PasswordHash.verify(entered,hash)){Ui.toast(this,"密码错误");return;}
                            new MediaService(this).deleteInspectionMedia(inspection.id);repo.permanentDelete(inspection.id);Ui.toast(this,"本机记录已永久删除");load();
                        }catch(Exception error){Ui.toast(this,"删除失败："+error.getMessage());}
                    }
                }).setNegativeButton("取消",null).show();
    }

    private void deleteWithCloudRecovery(Inspection inspection,char[] password){
        Ui.toast(this,"正在建立管理员恢复副本…");new Thread(()->{
            try{cloud.archiveAndPermanentlyDelete(inspection.id,password,null);runOnUiThread(()->{Ui.toast(this,"已永久删除；管理员恢复副本已保存");load();});}
            catch(Exception error){runOnUiThread(()->Ui.toast(this,"删除失败："+error.getMessage()));}
        },"trash-cloud-delete").start();
    }

    private void loadAdminRecovery(){
        Ui.toast(this,"正在读取管理员恢复库…");new Thread(()->{
            try{List<CloudSyncService.RecoveryEntry> entries=cloud.listAdminRecovery();runOnUiThread(()->showRecovery(entries));}
            catch(Exception error){runOnUiThread(()->Ui.toast(this,"读取失败："+error.getMessage()));}
        },"admin-recovery-list").start();
    }

    private void showRecovery(List<CloudSyncService.RecoveryEntry> entries){
        if(entries.isEmpty()){Ui.toast(this,"管理员恢复库为空");return;}
        String[] labels=new String[entries.size()];for(int i=0;i<entries.size();i++){
            CloudSyncService.RecoveryEntry e=entries.get(i);String when=e.deletedAt()>0?DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(e.deletedAt())):"未知时间";
            labels[i]=when+" · 记录 "+shortId(e.inspectionId());
        }
        new AlertDialog.Builder(this).setTitle("管理员恢复库").setItems(labels,(d,which)->confirmRecovery(entries.get(which))).setNegativeButton("关闭",null).show();
    }

    private void confirmRecovery(CloudSyncService.RecoveryEntry entry){
        new AlertDialog.Builder(this).setTitle("恢复这条检查记录？").setMessage("恢复后会生成一个比删除标记更新的记录版本，并在下一次检查内容同步时重新发送到其他设备。")
                .setPositiveButton("恢复",(d,w)->new Thread(()->{
                    try{cloud.restoreAdminRecovery(entry.name());runOnUiThread(()->{Ui.toast(this,"检查记录已从管理员恢复库找回");load();});}
                    catch(Exception error){runOnUiThread(()->Ui.toast(this,"恢复失败："+error.getMessage()));}
                },"admin-recovery-restore").start()).setNegativeButton("取消",null).show();
    }

    private String shortId(String id){return id==null?"未知":id.substring(0,Math.min(8,id.length()));}
}
''', encoding='utf-8')

print('Android 1.2.16 patch applied')
