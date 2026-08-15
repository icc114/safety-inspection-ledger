from pathlib import Path

p=Path('desktop/src/main/java/cn/safetyledger/pc/WordExporter.java')
t=p.read_text(encoding='utf-8')
old='''        CTFonts fonts = rPr.isSetRFonts() ? rPr.getRFonts() : rPr.addNewRFonts();'''
new='''        CTFonts fonts = rPr.addNewRFonts();'''
if old in t:
    t=t.replace(old,new,1)
p.write_text(t,encoding='utf-8')
print('Fixed WordExporter CTFonts compatibility for poi-ooxml-lite')
