from pathlib import Path

# Normalize Java regexes to forms that survive Python -> Java code generation without
# backslash escaping surprises.
p = Path('app/src/main/java/cn/safetyledger/app/media/MediaService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('line.replaceAll("\\s*\\d{6}$", "")',
                    'line.replaceAll("[ ]*[0-9]{6}$", "")')
p.write_text(text, encoding='utf-8')

p = Path('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java')
text = p.read_text(encoding='utf-8')
text = text.replace(
    'text.matches(".*[-+]?\\d{2,3}\\.\\d{3,}.*[-+]?\\d{2,3}\\.\\d{3,}.*")',
    'text.matches(".*[-+]?[0-9]{2,3}[.][0-9]{3,}.*[-+]?[0-9]{2,3}[.][0-9]{3,}.*")')
p.write_text(text, encoding='utf-8')

# The main patch splits the former RUNNING lock into independent content/device locks.
# A broad text replacement can produce doubled names in generated source; normalize them.
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('CONTENT_DEVICE_RUNNING', 'CONTENT_RUNNING')
text = text.replace('DEVICE_DEVICE_RUNNING', 'DEVICE_RUNNING')
p.write_text(text, encoding='utf-8')

print('Generated Java source normalized')
