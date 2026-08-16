from pathlib import Path

path = Path('app/src/main/java/cn/safetyledger/app/LedgerActivity.java')
text = path.read_text(encoding='utf-8')
old = 'Ui.text(this, "", 8.5f, true)'
new = 'Ui.text(this, "", 9, true)'
if old in text:
    path.write_text(text.replace(old, new, 1), encoding='utf-8')
    print('Fixed Ui.text integer text size')
elif new in text:
    print('Already fixed')
else:
    raise SystemExit('Expected Ui.text call not found')
