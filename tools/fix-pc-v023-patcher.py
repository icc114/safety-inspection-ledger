from pathlib import Path

p = Path('tools/apply-pc-v023-clarity-sync-signal.py')
s = p.read_text(encoding='utf-8')
old = "s = sub_once(s, r'    public static void main\\(String\\[\\]args\\)\\{.*?\\n    \\}\\n\\}', main_repl + '}', 'main ui defaults')"
new = """main_start = s.find('    public static void main(String[]args){')
if main_start < 0:
    raise SystemExit('missing marker: desktop main')
class_end = s.rfind('\\n}')
if class_end < main_start:
    raise SystemExit('missing marker: desktop class end')
s = s[:main_start] + main_repl + s[class_end:]"""
if old not in s:
    raise SystemExit('main replacement line not found')
p.write_text(s.replace(old, new, 1), encoding='utf-8')
print('patcher fixed')
