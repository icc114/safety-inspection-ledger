package cn.safetyledger.pc;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public final class SafetyLedgerDesktop extends JFrame {
    private final JTextField endpoint = new JTextField();
    private final JTextField space = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JTextField archive = new JTextField();
    private final JLabel status = new JLabel("尚未同步");
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"日期","时间","检查记录","地点","状态","本地文件夹"},0){@Override public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table = new JTable(model);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"safety-ledger-pc-auto-sync");t.setDaemon(true);return t;});
    private final Object syncLock = new Object();
    private volatile boolean syncing;
    private PcConfig config;

    public SafetyLedgerDesktop(){
        super("安全检查台账 PC");config=PcConfig.load();buildUi();loadFields();refreshTable();
        setSize(1050,680);setLocationRelativeTo(null);setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){scheduler.shutdownNow();}});
        scheduler.scheduleWithFixedDelay(()->{if(!config.endpoint.isBlank()&&!config.password.isBlank())sync(false);},20,120,TimeUnit.SECONDS);
    }

    private void buildUi(){
        setLayout(new BorderLayout(10,10));JPanel north=new JPanel(new BorderLayout(8,8));north.setBorder(BorderFactory.createEmptyBorder(12,12,0,12));
        JPanel fields=new JPanel(new GridLayout(4,2,8,7));fields.add(new JLabel("云同步地址"));fields.add(endpoint);fields.add(new JLabel("同步空间"));fields.add(space);fields.add(new JLabel("同步空间密码"));fields.add(password);
        JPanel archiveRow=new JPanel(new BorderLayout(5,0));archiveRow.add(archive,BorderLayout.CENTER);JButton choose=new JButton("选择文件夹");choose.addActionListener(e->chooseArchive());archiveRow.add(choose,BorderLayout.EAST);fields.add(new JLabel("电脑本地资料库"));fields.add(archiveRow);north.add(fields,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));JButton save=new JButton("保存设置");save.addActionListener(e->saveConfig());JButton test=new JButton("测试连接");test.addActionListener(e->testConnection());JButton sync=new JButton("立即同步检查内容");sync.addActionListener(e->sync(true));JButton importPackage=new JButton("导入手机数据包");importPackage.addActionListener(e->importPackage());JButton exportPackage=new JButton("导出手机兼容数据包");exportPackage.addActionListener(e->exportPortable());JButton open=new JButton("打开资料库");open.addActionListener(e->openArchive());
        actions.add(save);actions.add(test);actions.add(sync);actions.add(importPackage);actions.add(exportPackage);actions.add(open);north.add(actions,BorderLayout.SOUTH);add(north,BorderLayout.NORTH);
        table.setAutoCreateRowSorter(true);table.setRowHeight(26);table.getColumnModel().getColumn(5).setPreferredWidth(330);add(new JScrollPane(table),BorderLayout.CENTER);
        JPanel south=new JPanel(new BorderLayout());south.setBorder(BorderFactory.createEmptyBorder(0,12,12,12));JLabel note=new JLabel("同步后的记录会长期保存到上面指定的本地文件夹；云端仅作为设备间传输通道。Word 被人工修改后，后续同步不会覆盖，会另生成“系统更新”版本。");note.setForeground(new Color(80,80,80));south.add(note,BorderLayout.NORTH);south.add(status,BorderLayout.SOUTH);add(south,BorderLayout.SOUTH);
    }

    private void loadFields(){endpoint.setText(config.endpoint);space.setText(config.space);password.setText(config.password);archive.setText(config.archiveRoot);}
    private boolean saveConfig(){
        try{config.endpoint=endpoint.getText().trim();config.space=space.getText().trim();config.password=new String(password.getPassword());config.archiveRoot=archive.getText().trim();if(config.space.isBlank())config.space="safety-ledger";if(config.archiveRoot.isBlank())throw new IllegalArgumentException("请选择电脑本地资料库文件夹");Files.createDirectories(config.archivePath());config.save();setStatus("设置已保存");return true;}catch(Exception e){showError("保存失败",e);return false;}
    }

    private void chooseArchive(){JFileChooser chooser=new JFileChooser(config.archiveRoot);chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);chooser.setDialogTitle("选择安全检查台账本地资料库");if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)archive.setText(chooser.getSelectedFile().toPath().toString());}
    private void openArchive(){try{if(!saveConfig())return;Desktop.getDesktop().open(config.archivePath().toFile());}catch(Exception e){showError("打开资料库失败",e);}}

    private void testConnection(){if(!saveConfig())return;runTask("正在测试云端读写…",()->{CloudClient client=new CloudClient(config.endpoint,config.space,config.password.toCharArray());client.testReadWrite();client.registerPcDevice(config.deviceId,config.deviceName);return "连接成功；本电脑已登记到设备管理";},null);}

    private void sync(boolean manual){
        synchronized(syncLock){if(syncing){if(manual)setStatus("已有同步正在进行");return;}syncing=true;}
        SwingUtilities.invokeLater(()->setStatus("正在同步检查内容…"));
        CompletableFuture.runAsync(()->{
            try{
                if(manual&&!saveConfigOnEdt())return;
                if(config.endpoint.isBlank()||config.password.isBlank())return;
                Files.createDirectories(config.privateDir());Path cache=config.privateDir().resolve("cloud-cache");Files.createDirectories(cache);
                Properties fingerprints=load(config.privateDir().resolve("cloud-fingerprints.properties"));
                CloudClient client=new CloudClient(config.endpoint,config.space,config.password.toCharArray());client.prepare();client.registerPcDevice(config.deviceId,config.deviceName);
                List<String> names=client.listSnapshots();ArchiveService archiveService=new ArchiveService(config.archivePath());int changed=0,records=0;
                for(int i=0;i<names.size();i++){
                    String name=names.get(i);String fp=client.fingerprint(name);Path local=cache.resolve(safeFile(name));boolean needs=!fp.equals(fingerprints.getProperty(name,""))||!Files.isRegularFile(local);
                    SwingUtilities.invokeLater(()->setStatus("正在检查设备快照："+name));
                    if(needs){client.download(name,local);try(DataPackageCodec.ExtractedPackage pkg=DataPackageCodec.extract(local,config.password.toCharArray())){List<ArchiveService.Record> written=archiveService.process(pkg,"云同步 · "+name);records+=written.size();Path latest=config.privateDir().resolve("latest");DataPackageCodec.copyTree(pkg.root,latest);}fingerprints.setProperty(name,fp);changed++;}
                }
                store(config.privateDir().resolve("cloud-fingerprints.properties"),fingerprints);int finalChanged=changed,finalRecords=records;
                SwingUtilities.invokeLater(()->{refreshTable();setStatus("同步完成 · 更新快照 "+finalChanged+" 个"+(finalRecords>0?" · 处理记录 "+finalRecords+" 条":"")+" · "+now());});
            }catch(Exception e){SwingUtilities.invokeLater(()->showError("同步失败",e));}
            finally{synchronized(syncLock){syncing=false;}}
        });
    }

    private boolean saveConfigOnEdt(){if(SwingUtilities.isEventDispatchThread())return saveConfig();final boolean[] ok={false};try{SwingUtilities.invokeAndWait(()->ok[0]=saveConfig());}catch(Exception ignored){}return ok[0];}

    private void importPackage(){
        if(!saveConfig())return;JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("选择手机导出的 .safetydata 数据包");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path file=chooser.getSelectedFile().toPath();
        runTask("正在读取手机数据包…",()->{try(DataPackageCodec.ExtractedPackage pkg=DataPackageCodec.extract(file,config.password.toCharArray())){ArchiveService service=new ArchiveService(config.archivePath());int count=service.process(pkg,"本地导入 · "+file.getFileName()).size();DataPackageCodec.copyTree(pkg.root,config.privateDir().resolve("latest"));return "导入完成 · 已处理 "+count+" 条检查记录";}},this::refreshTable);
    }

    private void exportPortable(){
        if(!saveConfig())return;Path latest=config.privateDir().resolve("latest");if(!Files.isRegularFile(latest.resolve("database.sqlite"))){JOptionPane.showMessageDialog(this,"还没有可导出的同步数据。请先同步云端或导入手机数据包。","提示",JOptionPane.INFORMATION_MESSAGE);return;}
        JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("安全检查台账-电脑导出-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))+".safetydata"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path out=chooser.getSelectedFile().toPath();
        runTask("正在生成手机兼容数据包…",()->{DataPackageCodec.createPortable(latest,out);return "数据包已导出，可由 Android 或其他 PC 端直接识别";},null);
    }

    private void refreshTable(){
        try{ArchiveService service=new ArchiveService(Path.of(archive.getText().isBlank()?config.archiveRoot:archive.getText()));List<ArchiveService.IndexEntry> rows=service.listIndex();model.setRowCount(0);for(ArchiveService.IndexEntry e:rows)model.addRow(new Object[]{e.date,e.time,e.title,e.location,e.status,e.folder.toString()});}
        catch(Exception e){setStatus("读取本地资料库失败："+e.getMessage());}
    }

    private <T> void runTask(String message,Callable<T> work,Runnable after){setStatus(message);CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception e){throw new CompletionException(e);}}).whenComplete((result,error)->SwingUtilities.invokeLater(()->{if(error!=null){Throwable cause=error instanceof CompletionException&&error.getCause()!=null?error.getCause():error;showError("操作失败",cause);}else{setStatus(String.valueOf(result)+" · "+now());if(after!=null)after.run();}}));}
    private void setStatus(String text){status.setText(text);}
    private void showError(String title,Throwable error){String msg=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();setStatus(title+"："+msg);JOptionPane.showMessageDialog(this,msg,title,JOptionPane.ERROR_MESSAGE);}
    private static String now(){return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));}
    private static String safeFile(String name){return name.replaceAll("[^A-Za-z0-9._-]","_");}
    private static Properties load(Path file)throws Exception{Properties p=new Properties();if(Files.isRegularFile(file))try(var in=Files.newInputStream(file)){p.load(in);}return p;}
    private static void store(Path file,Properties p)throws Exception{Files.createDirectories(file.getParent());try(var out=Files.newOutputStream(file)){p.store(out,"Safety Ledger PC cloud fingerprints");}}

    public static void main(String[] args){SwingUtilities.invokeLater(()->{try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}new SafetyLedgerDesktop().setVisible(true);});}
}
