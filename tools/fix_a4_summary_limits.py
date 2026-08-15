from pathlib import Path

# Keep the printed title inside A4 width even after appending “记录表”.
p=Path('app/src/main/java/cn/safetyledger/app/TemplateActivity.java')
t=p.read_text(encoding='utf-8').replace('private static final int MAX_TEMPLATE_NAME = 24;', 'private static final int MAX_TEMPLATE_NAME = 16;')
p.write_text(t,encoding='utf-8')

# The full problem text already lives in each inspection row. Keep the bottom summary compact so
# it never crowds the fixed signature area even when all nine items fail.
p=Path('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java')
t=p.read_text(encoding='utf-8')
old='''        String details = problems.isEmpty() ? "无" : String.join("；", problems);
        wrapped(canvas, details, split + 6, y + 36,
                WIDTH - MARGIN - split - 12, 9, 3, Paint.Align.LEFT);'''
new='''        String details;
        if (problems.isEmpty()) {
            details = "无";
        } else {
            List<String> problemNumbers = new ArrayList<>();
            for (InspectionItem item : record.items) if ("FAIL".equals(item.result)) problemNumbers.add(String.valueOf(item.order));
            details = "第 " + String.join("、", problemNumbers) + " 项发现问题，具体问题及整改要求详见上表。";
        }
        wrapped(canvas, details, split + 6, y + 36,
                WIDTH - MARGIN - split - 12, 9, 3, Paint.Align.LEFT);'''
if old not in t: raise SystemExit('PdfExporter summary block not found')
t=t.replace(old,new,1)
p.write_text(t,encoding='utf-8')

p=Path('desktop/src/main/java/cn/safetyledger/pc/WordExporter.java')
t=p.read_text(encoding='utf-8')
old='''        String opinion = problems.isEmpty() ? "无" : String.join("；", problems);'''
new='''        String opinion;
        if (problems.isEmpty()) opinion = "无";
        else {
            List<String> numbers = new ArrayList<>();
            for (ArchiveService.Item item : record.items) if ("FAIL".equals(item.result)) numbers.add(String.valueOf(item.order));
            opinion = "第 " + String.join("、", numbers) + " 项发现问题，具体问题及整改要求详见上表。";
        }'''
if old not in t: raise SystemExit('WordExporter summary block not found')
t=t.replace(old,new,1)
p.write_text(t,encoding='utf-8')

# Re-apply cloud trash state after merging peer snapshots, so a stale peer tombstone cannot
# override an administrator RESTORED signal in the same sync pass.
p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
t=p.read_text(encoding='utf-8')
old='''            applyTombstones();

            progress(listener, "正在上传本机最新数据…");'''
new='''            syncTrashSignalsInternal(client,config,false);
            applyTombstones();

            progress(listener, "正在上传本机最新数据…");'''
if old not in t: raise SystemExit('CloudSyncService tombstone block not found')
t=t.replace(old,new,1)
p.write_text(t,encoding='utf-8')

print('Tightened A4 title limit, compacted problem summary, and hardened restore/delete signal precedence')
