from pathlib import Path

p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
text = p.read_text(encoding='utf-8')
old = 'Inspection existing=repo.inspection(inspectionId);if(existing==null)throw new IllegalArgumentException("检查记录不存在");'
new = 'if(repo.inspection(inspectionId)==null)throw new IllegalArgumentException("检查记录不存在");'
if old not in text:
    raise SystemExit('CloudSyncService generated inspection type line not found')
p.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Android v1.2.16 compile fix applied')
