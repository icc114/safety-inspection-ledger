from pathlib import Path

root = Path(__file__).resolve().parents[1]

ledger = root / 'app/src/main/java/cn/safetyledger/app/LedgerActivity.java'
text = ledger.read_text(encoding='utf-8')
text = text.replace('compact ? 6.8f : 8f, true', 'compact ? 7 : 8, true')
text = text.replace('compact ? 6.8f : 8f), Ui.weight(1)', 'compact ? 7 : 8), Ui.weight(1)')
text = text.replace('private TextView progressNumber(String value, int color, float sizeSp)',
                    'private TextView progressNumber(String value, int color, int sizeSp)')
text = text.replace('Ui.text(this, label, 6.5f, false)', 'Ui.text(this, label, 7, false)')
text = text.replace('Ui.text(this, value, 7.5f, true)', 'Ui.text(this, value, 8, true)')
text = text.replace('Ui.text(this, value, 6.8f, true)', 'Ui.text(this, value, 7, true)')
ledger.write_text(text, encoding='utf-8')

donut = root / 'app/src/main/java/cn/safetyledger/app/DonutProgressView.java'
d = donut.read_text(encoding='utf-8')
d = d.replace('Ui.dp(getContext(), compact ? 2.5f : 4f)', 'Ui.dp(getContext(), compact ? 3 : 4)')
d = d.replace('Ui.dp(getContext(), compact ? 7.2f : 12f)', 'Ui.dp(getContext(), compact ? 7 : 12)')
d = d.replace('Ui.dp(getContext(), 4.8f)', 'Ui.dp(getContext(), 5)')
donut.write_text(d, encoding='utf-8')

seg = root / 'app/src/main/java/cn/safetyledger/app/SegmentedProgressView.java'
s = seg.read_text(encoding='utf-8')
s = s.replace('Ui.dp(context, 3.2f)', 'Ui.dp(context, 3)')
s = s.replace('Ui.dp(getContext(), 4f)', 'Ui.dp(getContext(), 4)')
s = s.replace('Ui.dp(getContext(), 9.2f)', 'Ui.dp(getContext(), 9)')
s = s.replace('Ui.dp(getContext(), 6f)', 'Ui.dp(getContext(), 6)')
seg.write_text(s, encoding='utf-8')

print('Fixed Android 1.2.33 UI helper type compatibility.')
