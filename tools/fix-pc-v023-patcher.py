from pathlib import Path

p = Path('tools/apply-pc-v023-clarity-sync-signal.py')
s = p.read_text(encoding='utf-8')

# re.sub interprets backslashes in replacement strings. The generated Java contains \n escape
# sequences, so return the replacement from a function to preserve it byte-for-byte.
old_sub = "out, count = re.subn(pattern, replacement, text, count=1, flags=re.S)"
new_sub = "out, count = re.subn(pattern, lambda _m: replacement, text, count=1, flags=re.S)"
if old_sub not in s:
    raise SystemExit('sub_once replacement line not found')
s = s.replace(old_sub, new_sub, 1)

old_main = "s = sub_once(s, r'    public static void main\\(String\\[\\]args\\)\\{.*?\\n    \\}\\n\\}', main_repl + '}', 'main ui defaults')"
new_main = """main_start = s.find('    public static void main(String[]args){')
if main_start < 0:
    raise SystemExit('missing marker: desktop main')
class_end = s.rfind('\\n}')
if class_end < main_start:
    raise SystemExit('missing marker: desktop class end')
s = s[:main_start] + main_repl + s[class_end:]"""
if old_main not in s:
    raise SystemExit('main replacement line not found')
s = s.replace(old_main, new_main, 1)

p.write_text(s, encoding='utf-8')
print('patcher fixed: literal replacements + desktop main')
