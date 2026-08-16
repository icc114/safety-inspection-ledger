from pathlib import Path

replacements = {
    'app/src/main/java/cn/safetyledger/app/MonthlyPlanActivity.java': {
        '12.5f, false': '12, false',
        '11.5f, false': '11, false',
    },
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java': {
        '8.5f, true': '8, true',
        '8.5f, false': '8, false',
    },
}

for filename, mapping in replacements.items():
    path = Path(filename)
    text = path.read_text(encoding='utf-8')
    for old, new in mapping.items():
        if old not in text:
            raise SystemExit(f'{filename}: expected {old!r}')
        text = text.replace(old, new)
    path.write_text(text, encoding='utf-8')
