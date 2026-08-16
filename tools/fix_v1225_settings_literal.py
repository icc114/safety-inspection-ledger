from pathlib import Path

path = Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
text = path.read_text(encoding='utf-8')
start_token = '.setMessage(message + "'
message_token = '已自动写入同步诊断日志。请在本页点击“查看 / 导出同步日志”，导出 TXT 后即可直接发给开发者排查。")'

start = text.find(start_token)
if start < 0:
    raise SystemExit('sync failure AlertDialog setMessage start not found')
end_marker = text.find(message_token, start)
if end_marker < 0:
    # Already fixed source should contain the escaped form on one line.
    expected = '.setMessage(message + "\\n\\n' + message_token
    if expected in text:
        print('SettingsActivity diagnostics message already fixed')
        raise SystemExit(0)
    raise SystemExit('sync diagnostics message body not found')

end = end_marker + len(message_token)
replacement = '.setMessage(message + "\\n\\n' + message_token
text = text[:start] + replacement + text[end:]
path.write_text(text, encoding='utf-8')
print('Fixed SettingsActivity sync diagnostics Java string literal')
