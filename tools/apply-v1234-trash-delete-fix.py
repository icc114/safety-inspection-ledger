from pathlib import Path
import re

ROOT = Path('.')
repo_file = ROOT / 'app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java'
cloud_file = ROOT / 'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java'
trash_file = ROOT / 'app/src/main/java/cn/safetyledger/app/TrashActivity.java'
gradle_file = ROOT / 'app/build.gradle'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing marker: {label}')
    if text.count(old) != 1:
        raise SystemExit(f'expected exactly one marker for {label}, got {text.count(old)}')
    return text.replace(old, new, 1)

# 1) Fix the actual SQLite constraint failure. The previous permanentDeleteAt SQL omitted
# both tombstones.id and tombstones.revision even though revision is NOT NULL.
s = repo_file.read_text(encoding='utf-8')
old = '''    public void permanentDeleteAt(String id,long deletedAt){SQLiteDatabase d=raw();d.beginTransaction();try{d.delete("inspections","id=?",new String[]{id});d.execSQL("INSERT OR REPLACE INTO tombstones(entity_type,entity_id,deleted_at,synced_at) VALUES('inspection',?,?,NULL)",new Object[]{id,deletedAt});d.setTransactionSuccessful();}finally{d.endTransaction();}}'''
new = '''    public void permanentDeleteAt(String id,long deletedAt){
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
    }'''
s = replace_once(s, old, new, 'permanentDeleteAt tombstone insert')
repo_file.write_text(s, encoding='utf-8')

# 2) Never physically delete photos before the database transaction succeeds. This prevents
# a local SQLite failure from leaving a recycle-bin record whose files have already vanished.
s = cloud_file.read_text(encoding='utf-8')
s = replace_once(
    s,
    'if(repo.inspection(id)!=null){new MediaService(context).deleteInspectionMedia(id);repo.permanentDeleteAt(id,deletedAt);deleted++;}',
    'if(repo.inspection(id)!=null){repo.permanentDeleteAt(id,deletedAt);new MediaService(context).deleteInspectionMedia(id);deleted++;}',
    'remote trash signal delete ordering')
s = replace_once(
    s,
    'new MediaService(context).deleteInspectionMedia(inspectionId);repo.permanentDeleteAt(inspectionId,now);CloudSyncScheduler.scheduleTrashSoon(context);',
    'repo.permanentDeleteAt(inspectionId,now);new MediaService(context).deleteInspectionMedia(inspectionId);SyncLog.info(context,"云端回收站删除","本地删除完成 · inspection="+inspectionId);CloudSyncScheduler.scheduleTrashSoon(context);',
    'local cloud trash delete ordering')
cloud_file.write_text(s, encoding='utf-8')

# 3) Local-only deletion gets the same safe ordering. Cloud deletion failures are also written
# to the existing diagnostic log, so a local DB error no longer produces “暂无同步日志”.
s = trash_file.read_text(encoding='utf-8')
s = replace_once(
    s,
    'import cn.safetyledger.app.sync.CloudSyncService;import cn.safetyledger.app.sync.SyncErrorFormatter;',
    'import cn.safetyledger.app.sync.CloudSyncService;import cn.safetyledger.app.sync.SyncErrorFormatter;import cn.safetyledger.app.sync.SyncLog;',
    'TrashActivity SyncLog import')
s = replace_once(
    s,
    'new MediaService(this).deleteInspectionMedia(inspection.id);repo.permanentDelete(inspection.id);Ui.toast(this,"本机记录已永久删除");load();',
    'repo.permanentDelete(inspection.id);new MediaService(this).deleteInspectionMedia(inspection.id);Ui.toast(this,"本机记录已永久删除");load();',
    'local-only delete ordering')
s = replace_once(
    s,
    '}catch(Exception error){String msg=SyncErrorFormatter.format(error);runOnUiThread(()->new AlertDialog.Builder(this).setTitle(SyncErrorFormatter.isNetwork(error)?"网络连接问题":"删除失败").setMessage(msg).setPositiveButton("确定",null).show());}},"trash-cloud-delete").start();}',
    '}catch(Exception error){SyncLog.error(this,"回收站永久删除",error);String msg=SyncErrorFormatter.format(error);runOnUiThread(()->new AlertDialog.Builder(this).setTitle(SyncErrorFormatter.isNetwork(error)?"网络连接问题":"删除失败").setMessage(msg).setPositiveButton("确定",null).show());}},"trash-cloud-delete").start();}',
    'cloud delete error logging')
trash_file.write_text(s, encoding='utf-8')

# 4) Version bump. This must remain upgrade-compatible with the permanent signing key.
s = gradle_file.read_text(encoding='utf-8')
s, n1 = re.subn(r'versionCode\s+36\b', 'versionCode 37', s, count=1)
s, n2 = re.subn(r"versionName\s+'1\.2\.33'", "versionName '1.2.34'", s, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit(f'version markers not found: code={n1} name={n2}')
gradle_file.write_text(s, encoding='utf-8')

# Contract checks: fail the workflow rather than committing a partial/unsafe patch.
repo_text = repo_file.read_text(encoding='utf-8')
if "tombstones(entity_type,entity_id,deleted_at,synced_at)" in repo_text:
    raise SystemExit('unsafe legacy permanentDeleteAt SQL still present')
if '"revision",1' not in repo_text:
    raise SystemExit('tombstone revision is not explicitly populated')
cloud_text = cloud_file.read_text(encoding='utf-8')
if 'deleteInspectionMedia(inspectionId);repo.permanentDeleteAt' in cloud_text:
    raise SystemExit('unsafe media-before-database delete ordering still present')

print('Android 1.2.34 recycle-bin permanent-delete fix applied')
