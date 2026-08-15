from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')

# Android 1.2.17: fix portable restore on the real app_settings schema and keep legacy compatibility.
replace('app/build.gradle', "versionCode 19\n        versionName '1.2.16'", "versionCode 20\n        versionName '1.2.17'")
replace(
    'app/src/main/java/cn/safetyledger/app/backup/BackupService.java',
    '''        if(tableExists(d,"main","app_settings")){
            d.delete("app_settings","key IN ('device_id','cloud_role','device_role','last_sync_at','last_sync_error')",null);
        }
''',
    '''        if(tableExists(d,"main","app_settings")){
            // Current schema uses setting_key; very old experimental backups may use key.
            // Resolve the real column before deleting device-specific state so a valid
            // cross-device .safetydata import can never fail with "no such column: key".
            Set<String> settingColumns=columns(d,"main","app_settings");
            String keyColumn=settingColumns.contains("setting_key")?"setting_key":settingColumns.contains("key")?"key":null;
            if(keyColumn!=null){
                String[] localOnly={"device_id","cloud_role","device_role","last_sync_at","last_sync_error"};
                d.delete("app_settings",keyColumn+" IN (?,?,?,?,?)",localOnly);
            }
        }
'''
)

# PC 0.1.1: clicking the inspection title, double-clicking a row, or pressing Enter opens preview.
p = Path('desktop/src/main/java/cn/safetyledger/pc/SafetyLedgerDesktop.java')
t = p.read_text(encoding='utf-8')
t = t.replace('import java.awt.event.WindowAdapter;\nimport java.awt.event.WindowEvent;\n',
'''import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
''', 1)
old = '''        table.setAutoCreateRowSorter(true);table.setRowHeight(26);table.getColumnModel().getColumn(5).setPreferredWidth(330);add(new JScrollPane(table),BorderLayout.CENTER);
'''
new = '''        table.setAutoCreateRowSorter(true);table.setRowHeight(26);table.getColumnModel().getColumn(5).setPreferredWidth(330);
        table.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){
            int viewRow=table.rowAtPoint(e.getPoint()),viewCol=table.columnAtPoint(e.getPoint());
            if(viewRow<0)return;
            if(viewCol==2||e.getClickCount()>=2)openRecordPreview(viewRow);
        }});
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"),"preview-record");
        table.getActionMap().put("preview-record",new AbstractAction(){@Override public void actionPerformed(java.awt.event.ActionEvent e){int row=table.getSelectedRow();if(row>=0)openRecordPreview(row);}});
        table.setToolTipText("点击“检查记录”可预览；双击任意行也可打开预览");
        add(new JScrollPane(table),BorderLayout.CENTER);
'''
if old not in t: raise SystemExit('PC table block not found')
t = t.replace(old, new, 1)
anchor = '''    private void refreshTable(){
'''
method = '''    private void openRecordPreview(int viewRow){
        try{
            int modelRow=table.convertRowIndexToModel(viewRow);
            Object value=model.getValueAt(modelRow,5);
            if(value==null)throw new IllegalStateException("该记录没有本地资料路径");
            Path folder=Path.of(String.valueOf(value));
            RecordPreviewDialog.open(this,folder);
        }catch(Exception error){showError("无法预览检查记录",error);}
    }

'''
if anchor not in t: raise SystemExit('refreshTable anchor not found')
t = t.replace(anchor, method + anchor, 1)
p.write_text(t, encoding='utf-8')

# PC version.
replace('desktop/pom.xml', '<version>0.1.0</version>', '<version>0.1.1</version>')
replace('desktop/pom.xml', '<finalName>safety-ledger-pc-0.1.0-all</finalName>', '<finalName>safety-ledger-pc-0.1.1-all</finalName>')

p = Path('desktop/README.md')
t = p.read_text(encoding='utf-8')
t = t.replace('PC 0.1.0 当前重点', 'PC 0.1.1 当前重点')
needle = '- 可自行选择电脑本地资料库，例如 `D:\\安全检查台账资料库`。\n'
if needle in t:
    t = t.replace(needle, needle + '- 主列表点击“检查记录”、双击任意记录或按 Enter，可弹窗预览检查基本信息、检查事项、整改/复查以及照片缩略图，并可直接打开 Word 或本地文件夹。\n', 1)
p.write_text(t, encoding='utf-8')

print('Android 1.2.17 and PC 0.1.1 patch applied')
