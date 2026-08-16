from pathlib import Path

p = Path('app/src/main/java/cn/safetyledger/app/LedgerActivity.java')
text = p.read_text(encoding='utf-8')
bad = 'TextView title = Ui.text(this, "本月检查\n进度", 10, true);'.replace('\\n', '\n')
good = 'TextView title = Ui.text(this, "本月检查\\n进度", 10, true);'
if good in text:
    print('Java newline escape already fixed.')
elif bad in text:
    p.write_text(text.replace(bad, good, 1), encoding='utf-8')
    print('Fixed Java newline escape.')
else:
    raise SystemExit('Expected progress-title string not found.')
