from pathlib import Path

# The main patch intentionally keeps regexes simple. Normalize two generated Java
# expressions to forms that do not depend on backslash escaping through Python->Java.
p = Path('app/src/main/java/cn/safetyledger/app/media/MediaService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('line.replaceAll("\\s*\\d{6}$", "")',
                    'line.replaceAll("[ ]*[0-9]{6}$", "")')
# Also handle the exact invalid text produced by the first patch when Python consumed slashes.
text = text.replace('line.replaceAll("\\s*\\d{6}$", "")',
                    'line.replaceAll("[ ]*[0-9]{6}$", "")')
text = text.replace('line.replaceAll("\\s*\\d{6}$", "")',
                    'line.replaceAll("[ ]*[0-9]{6}$", "")')
p.write_text(text, encoding='utf-8')

p = Path('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java')
text = p.read_text(encoding='utf-8')
# Match coordinate-like pairs without Java regex escape sequences.
old_variants = [
    'text.matches(".*[-+]?\\d{2,3}\\.\\d{3,}.*[-+]?\\d{2,3}\\.\\d{3,}.*")',
]
for old in old_variants:
    text = text.replace(old,
        'text.matches(".*[-+]?[0-9]{2,3}[.][0-9]{3,}.*[-+]?[0-9]{2,3}[.][0-9]{3,}.*")')
p.write_text(text, encoding='utf-8')

print('Java regex escaping normalized')
