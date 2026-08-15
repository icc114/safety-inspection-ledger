from pathlib import Path

p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
text = p.read_text(encoding='utf-8')
old = '''            // Publish presence first. Previously a bad/stale peer snapshot could fail before the
            // new device ever uploaded anything, so the administrator could never see/manage it.
            BackupService backup = new BackupService(context);
            progress(listener, "正在准备本机检查内容…");
            uploadSnapshot(backup, client, config, deviceId);

            int peers = 0;
'''
new = '''            // Device presence/roles now use the independent device-control channel. Content sync
            // therefore downloads/merges first and uploads only once at the end, avoiding the old
            // double-upload of every photo-heavy .safetydata snapshot.
            BackupService backup = new BackupService(context);

            int peers = 0;
'''
if old not in text:
    raise SystemExit('initial content upload block not found')
text = text.replace(old, new, 1)
text = text.replace('''            // Merged role data may contain an administrator's change for this device.
            registerCurrentDevice(deviceId, emptyCloud);
''', '''            // Device roles are intentionally not transported by inspection-content snapshots.
            registerCurrentDevice(deviceId, emptyCloud);
''', 1)
p.write_text(text, encoding='utf-8')

# Cloud content merge must never import device identity/provider settings. Those belong to the
# lightweight device-control/config channels, not to inspection business data.
p = Path('app/src/main/java/cn/safetyledger/app/backup/BackupService.java')
text = p.read_text(encoding='utf-8')
old = '''        String[] tables={"templates","template_items","inspections","inspection_items","media",
                "signatures","app_settings","sync_providers","tombstones",
                "archive_index","holiday_cache"};
'''
new = '''        String[] tables={"templates","template_items","inspections","inspection_items","media",
                "signatures","tombstones","archive_index","holiday_cache"};
'''
if old not in text:
    raise SystemExit('cloud merge table list not found')
text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')

print('v1.2.15 final efficiency changes applied')
