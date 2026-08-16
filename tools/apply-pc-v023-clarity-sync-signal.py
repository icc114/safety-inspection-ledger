from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def write(rel, text):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text.strip() + "\n", encoding="utf-8")

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

def sub_once(text, pattern, replacement, label):
    out, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"pattern count {count}: {label}")
    return out

write("desktop/src/main/java/cn/safetyledger/pc/PcConfig.java", r'''
package cn.safetyledger.pc;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;

/** Stores PC settings; the cloud password remains encrypted with a random key local to this profile. */
public final class PcConfig {
    private static final Path HOME = Path.of(System.getProperty("user.home"), ".safety-ledger-pc");
    private static final Path FILE = HOME.resolve("config.properties");
    private static final Path KEY = HOME.resolve("local.key");
    public String endpoint="", space="safety-ledger", password="",
            archiveRoot=Path.of(System.getProperty("user.home"),"安全检查台账资料库").toString();
    public String deviceId=UUID.randomUUID().toString(), deviceName=defaultName();
    public String shiftDates="";
    /** 0 disables background cloud checks; otherwise this is the lightweight signal polling interval. */
    public int syncIntervalMinutes=5;

    public static PcConfig load(){
        PcConfig c=new PcConfig();
        try{
            Files.createDirectories(HOME);
            if(!Files.isRegularFile(FILE))return c;
            Properties p=new Properties();try(InputStream in=Files.newInputStream(FILE)){p.load(in);}
            c.endpoint=p.getProperty("endpoint","");c.space=p.getProperty("space","safety-ledger");
            c.archiveRoot=p.getProperty("archiveRoot",c.archiveRoot);c.deviceId=p.getProperty("deviceId",c.deviceId);
            c.deviceName=p.getProperty("deviceName",defaultName());c.shiftDates=p.getProperty("shiftDates","");
            try{c.syncIntervalMinutes=Integer.parseInt(p.getProperty("syncIntervalMinutes","5"));}catch(Exception ignored){c.syncIntervalMinutes=5;}
            if(c.syncIntervalMinutes<0)c.syncIntervalMinutes=0;
            String secret=p.getProperty("password","");if(!secret.isBlank())c.password=decrypt(secret);return c;
        }catch(Exception ignored){return c;}
    }
    public void save()throws Exception{
        Files.createDirectories(HOME);Properties p=new Properties();p.setProperty("endpoint",endpoint);p.setProperty("space",space);
        p.setProperty("archiveRoot",archiveRoot);p.setProperty("deviceId",deviceId);p.setProperty("deviceName",deviceName);
        p.setProperty("shiftDates",shiftDates==null?"":shiftDates);p.setProperty("syncIntervalMinutes",String.valueOf(syncIntervalMinutes));
        p.setProperty("password",password.isBlank()?"":encrypt(password));try(OutputStream out=Files.newOutputStream(FILE)){p.store(out,"Safety Ledger PC config");}
    }
    public Path archivePath(){return Path.of(archiveRoot).toAbsolutePath().normalize();}
    public Path privateDir(){return archivePath().resolve(".safety-ledger");}
    public Set<LocalDate> shiftDateSet(){
        Set<LocalDate> out=new HashSet<>();if(shiftDates==null)return out;
        for(String part:shiftDates.split("[,;，；\\s]+"))try{if(!part.isBlank())out.add(LocalDate.parse(part.trim()));}catch(Exception ignored){}
        return out;
    }

    private static String encrypt(String text)throws Exception{byte[] key=localKey(),iv=random(12);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));byte[] all=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(encrypted,0,all,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(all);}
    private static String decrypt(String value)throws Exception{byte[] all=Base64.getDecoder().decode(value);if(all.length<29)return"";byte[] iv=Arrays.copyOfRange(all,0,12),data=Arrays.copyOfRange(all,12,all.length);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(localKey(),"AES"),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(data),StandardCharsets.UTF_8);}
    private static byte[] localKey()throws IOException{Files.createDirectories(HOME);if(Files.isRegularFile(KEY)){byte[] key=Files.readAllBytes(KEY);if(key.length==32)return key;}byte[] key=random(32);Files.write(KEY,key,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);return key;}
    private static byte[] random(int n){byte[] b=new byte[n];new SecureRandom().nextBytes(b);return b;}
    private static String defaultName(){String host=System.getenv("COMPUTERNAME");if(host==null||host.isBlank())host="Windows PC";return "电脑-"+host;}
}
''')

write("desktop/src/main/java/cn/safetyledger/pc/CrashLogger.java", r'''
package cn.safetyledger.pc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/** Records uncaught desktop exceptions so packaged Windows failures can be diagnosed after the fact. */
public final class CrashLogger {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static volatile Path directory;
    private static volatile boolean installed;
    private CrashLogger() {}

    public static synchronized void install(Path privateDir) {
        directory = privateDir.resolve("logs");
        try { Files.createDirectories(directory); purge(directory, 60); } catch (Exception ignored) {}
        if (installed) return;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record("未捕获异常 · 线程=" + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
        installed = true;
    }

    public static void record(String stage, Throwable error) {
        Path dir = directory;
        if (dir == null || error == null) return;
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("pc-crash-" + LocalDateTime.now().format(TS) + ".log");
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            String text = "安全检查台账 PC 0.2.3 崩溃日志\n"
                    + "时间: " + LocalDateTime.now() + "\n"
                    + "阶段: " + stage + "\n"
                    + "Java: " + System.getProperty("java.version") + "\n"
                    + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                    + "------------------------------------------------------------\n" + sw;
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception ignored) {}
    }

    public static Path latest(Path privateDir) {
        Path dir = privateDir.resolve("logs");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("pc-crash-") && p.getFileName().toString().endsWith(".log"))
                    .max(Comparator.comparingLong(CrashLogger::modified)).orElse(null);
        } catch (Exception ignored) { return null; }
    }

    public static String readTail(Path path, int maxChars) {
        if (path == null || !Files.isRegularFile(path)) return "暂无软件崩溃日志。";
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.length() <= maxChars ? text : "……仅显示日志末尾……\n" + text.substring(text.length() - maxChars);
        } catch (Exception error) { return "读取崩溃日志失败：" + error.getMessage(); }
    }

    private static long modified(Path p){try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception e){return Long.MIN_VALUE;}}
    private static void purge(Path dir,int days){
        long cutoff=System.currentTimeMillis()-days*24L*60L*60L*1000L;
        try(Stream<Path>s=Files.list(dir)){s.filter(Files::isRegularFile).forEach(p->{try{if(p.getFileName().toString().startsWith("pc-crash-")&&Files.getLastModifiedTime(p).toMillis()<cutoff)Files.deleteIfExists(p);}catch(Exception ignored){}});}catch(Exception ignored){}
    }
}
''')

write("desktop/src/main/java/cn/safetyledger/pc/AppIcon.java", r'''
package cn.safetyledger.pc;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Crisp multi-size vector icon. Shapes are intentionally simple so 16–64 px Windows rendering stays sharp. */
public final class AppIcon {
    private AppIcon() {}

    public static Image image(int size) {
        int target=Math.max(16,size), source=Math.max(512,target*8);
        BufferedImage hi=new BufferedImage(source,source,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=hi.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
            double k=source/100.0;g.scale(k,k);
            g.setColor(new Color(255,255,255,250));g.fill(new RoundRectangle2D.Double(1.5,1.5,97,97,21,21));
            g.setColor(new Color(20,105,224));g.fill(new RoundRectangle2D.Double(5,5,90,90,18,18));

            // document
            g.setColor(Color.WHITE);
            Path2D paper=new Path2D.Double();paper.moveTo(24,18);paper.lineTo(59,18);paper.lineTo(73,32);paper.lineTo(73,78);
            paper.curveTo(73,82,70,85,66,85);paper.lineTo(24,85);paper.curveTo(20,85,17,82,17,78);paper.lineTo(17,25);paper.curveTo(17,21,20,18,24,18);paper.closePath();g.fill(paper);
            g.setColor(new Color(216,234,255));Path2D fold=new Path2D.Double();fold.moveTo(59,18);fold.lineTo(73,32);fold.lineTo(64,32);fold.curveTo(61,32,59,30,59,27);fold.closePath();g.fill(fold);

            g.setStroke(new BasicStroke(4.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.setColor(new Color(20,105,224));
            check(g,25,39);check(g,25,56);check(g,25,73);
            g.setStroke(new BasicStroke(4.0f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawLine(41,39,61,39);g.drawLine(41,56,61,56);g.drawLine(41,73,55,73);

            // bottom-right shield
            Path2D shield=new Path2D.Double();shield.moveTo(75,55);shield.lineTo(92,61);shield.lineTo(92,73);shield.curveTo(92,84,85,91,75,95);shield.curveTo(65,91,58,84,58,73);shield.lineTo(58,61);shield.closePath();
            g.setColor(new Color(255,255,255));g.setStroke(new BasicStroke(6f,BasicStroke.JOIN_ROUND,BasicStroke.CAP_ROUND));g.draw(shield);
            g.setColor(new Color(255,178,25));g.fill(shield);
            g.setColor(Color.WHITE);g.setStroke(new BasicStroke(4.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawLine(66,74,72,80);g.drawLine(72,80,84,68);
        }finally{g.dispose();}
        BufferedImage out=new BufferedImage(target,target,BufferedImage.TYPE_INT_ARGB);Graphics2D d=out.createGraphics();
        try{d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);d.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);d.drawImage(hi,0,0,target,target,null);}finally{d.dispose();}
        return out;
    }
    public static Icon icon(int size){return new ImageIcon(image(size));}
    private static void check(Graphics2D g,int x,int y){g.drawLine(x,y,x+4,y+4);g.drawLine(x+4,y+4,x+10,y-5);}
}
''')

cloud = (ROOT / "desktop/src/main/java/cn/safetyledger/pc/CloudClient.java").read_text(encoding="utf-8")
cloud = cloud.replace('import java.net.URI;\n', 'import java.net.URI;\nimport java.net.InetAddress;\nimport java.net.Proxy;\nimport java.net.ProxySelector;\n')
cloud = replace_once(cloud,
'''    private final HttpClient http = HttpClient.newBuilder()\n            .connectTimeout(Duration.ofSeconds(15))\n            .followRedirects(HttpClient.Redirect.NORMAL)\n            .build();''',
'''    private final HttpClient http = buildHttpClient();''', 'http client')
cloud = replace_once(cloud,
'''    public void testReadWrite() throws Exception {\n        log("开始云端读写测试");\n        prepare();''',
'''    public void testReadWrite() throws Exception {\n        log("开始云端读写测试");\n        logNetworkEnvironment();\n        healthCheck();\n        prepare();''', 'test read write')
insert = r'''
    public String revision() throws Exception {
        URI uri = URI.create(spaceUrl() + ".sync-signal");
        HttpResponse<byte[]> response = send("GET", uri, HttpRequest.BodyPublishers.noBody(), null, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404 || response.statusCode() == 405) {
            log("云端未提供轻量更新信号接口，将兼容使用完整同步检查");
            return "";
        }
        if (response.statusCode() / 100 != 2) throw failure("读取云端更新信号失败", response.statusCode(), response.body());
        String text = new String(response.body(), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\\\"revision\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(text);
        String value = matcher.find() ? matcher.group(1) : text.trim();
        log("云端更新信号 · revision=" + (value.length() > 24 ? value.substring(0,24) : value));
        return value;
    }

    public void logNetworkEnvironment() {
        try {
            URI base = URI.create(endpoint);
            String host = base.getHost();
            log("网络诊断 · endpoint=" + safeUri(base) + " · java=" + System.getProperty("java.version"));
            if (host != null) {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                StringBuilder resolved = new StringBuilder();
                for (InetAddress address : addresses) { if (resolved.length() > 0) resolved.append(", "); resolved.append(address.getHostAddress()); }
                log("DNS 解析 · " + host + " -> " + resolved);
            }
            ProxySelector selector = ProxySelector.getDefault();
            List<Proxy> proxies = selector == null ? List.of() : selector.select(base);
            log("系统代理 · " + (proxies == null || proxies.isEmpty() ? "未检测到" : proxies.toString()));
        } catch (Exception error) {
            log("网络诊断失败 · " + error.getClass().getSimpleName() + " · " + String.valueOf(error.getMessage()));
        }
    }

    private void healthCheck() throws Exception {
        URI uri = URI.create(endpoint + "health");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET()
                    .header("User-Agent", "SafetyLedger-PC/0.2.3").build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String body = new String(response.body(), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            if (body.length() > 260) body = body.substring(0,260);
            log("健康检查 /health -> " + response.statusCode() + (body.isBlank() ? "" : " · " + body));
            if (response.statusCode() == 503) throw new IOException("云同步服务尚未部署完整：" + body);
        } catch (IOException error) {
            log("健康检查网络异常 · " + error.getClass().getSimpleName() + " · " + String.valueOf(error.getMessage()));
            throw error;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw interrupted;
        }
    }

    private static HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1);
        ProxySelector selector = ProxySelector.getDefault();
        if (selector != null) builder.proxy(selector);
        return builder.build();
    }
'''
cloud = replace_once(cloud, '\n    public void prepare() throws Exception {', '\n' + insert + '\n    public void prepare() throws Exception {', 'cloud methods insert')
cloud = cloud.replace('.header("User-Agent", "SafetyLedger-PC/0.2.2")', '.header("User-Agent", "SafetyLedger-PC/0.2.3")')
write("desktop/src/main/java/cn/safetyledger/pc/CloudClient.java", cloud)

sync = (ROOT / "desktop/src/main/java/cn/safetyledger/pc/SyncLogger.java").read_text(encoding="utf-8")
sync = sync.replace('安全检查台账 PC 0.2.2 同步日志', '安全检查台账 PC 0.2.3 网络/同步日志')
sync = sync.replace('                    + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\\n"',
'''                    + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\\n"\n                            + "系统代理: " + String.valueOf(java.net.ProxySelector.getDefault()) + "\\n"''')
write("desktop/src/main/java/cn/safetyledger/pc/SyncLogger.java", sync)

p = ROOT / "desktop/src/main/java/cn/safetyledger/pc/SafetyLedgerDesktop.java"
s = p.read_text(encoding="utf-8")
s = s.replace('安全检查台账 PC 0.2.2', '安全检查台账 PC 0.2.3')
s = replace_once(s, '    private volatile Path latestSyncLog;\n', '    private volatile Path latestSyncLog;\n    private volatile ScheduledFuture<?> autoSyncFuture;\n    private volatile String lastCloudRevision = "";\n', 'desktop fields')
s = replace_once(s,
'''        config = PcConfig.load();\n        holidayService = new HolidayCalendarService(config.privateDir());\n        setIconImage(AppIcon.image(64));''',
'''        config = PcConfig.load();\n        holidayService = new HolidayCalendarService(config.privateDir());\n        CrashLogger.install(config.privateDir());\n        setIconImages(List.of(AppIcon.image(16), AppIcon.image(20), AppIcon.image(24), AppIcon.image(32),\n                AppIcon.image(48), AppIcon.image(64), AppIcon.image(128), AppIcon.image(256)));''', 'constructor icon')
s = sub_once(s, r'''\n        scheduler\.scheduleWithFixedDelay\(\(\) -> \{.*?\n        \}, 20, 120, TimeUnit\.SECONDS\);''', '\n        scheduleAutoSync();', 'fixed scheduler')
s = s.replace('JLabel icon = new JLabel(AppIcon.icon(46));\n        icon.setPreferredSize(new Dimension(48, 48));', 'JLabel icon = new JLabel(AppIcon.icon(56));\n        icon.setPreferredSize(new Dimension(58, 58));')
s = s.replace('bar.setBorder(new EmptyBorder(10, 16, 10, 16));', 'bar.setBorder(new EmptyBorder(8, 18, 8, 18));')
s = s.replace('titleLabel.getFont().deriveFont(Font.BOLD, 24f)', 'titleLabel.getFont().deriveFont(Font.BOLD, 23f)')
s = s.replace('        button.setFocusPainted(false);\n        button.setFont(button.getFont().deriveFont(Font.BOLD, 14f));', '        button.setUI(new javax.swing.plaf.basic.BasicButtonUI());\n        button.setFocusPainted(false);\n        button.setOpaque(true);\n        button.setContentAreaFilled(true);\n        button.setFont(button.getFont().deriveFont(Font.BOLD, 14f));', 1)
s = s.replace('        table.setRowHeight(38);', '        table.setRowHeight(44);')
s = s.replace('table.getFont().deriveFont(13f)', 'table.getFont().deriveFont(14f)')
s = s.replace('table.getTableHeader().setPreferredSize(new Dimension(10, 40));', 'table.getTableHeader().setPreferredSize(new Dimension(10, 43));')
s = s.replace('table.getTableHeader().getFont().deriveFont(Font.BOLD, 13f)', 'table.getTableHeader().getFont().deriveFont(Font.BOLD, 14f)')
s = replace_once(s,
'''        JButton button = new JButton(text);\n        button.setFocusPainted(false);''',
'''        JButton button = new JButton(text);\n        button.setUI(new javax.swing.plaf.basic.BasicButtonUI());\n        button.setFocusPainted(false);\n        button.setOpaque(true);\n        button.setContentAreaFilled(true);''', 'action button basic ui')
s = s.replace('button.getFont().deriveFont(primary ? Font.BOLD : Font.PLAIN, 13f)', 'button.getFont().deriveFont(primary ? Font.BOLD : Font.PLAIN, 14f)')
s = s.replace('new Dimension(Math.max(primary ? 76 : 105, text.length() * 14 + 28), 34)', 'new Dimension(Math.max(primary ? 82 : 112, text.length() * 15 + 30), 36)')

settings_method = r'''
    private void showSettings() {
        JDialog dialog = new JDialog(this, "设置", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(0, 0));
        dialog.getContentPane().setBackground(BG);

        JTextField endpointField = new JTextField(config.endpoint, 40);
        JTextField spaceField = new JTextField(config.space, 28);
        JPasswordField passwordField = new JPasswordField(config.password, 28);
        JTextField archiveField = new JTextField(config.archiveRoot, 34);
        JTextField deviceField = new JTextField(config.deviceName, 28);
        JTextField shiftField = new JTextField(config.shiftDates, 34);
        deviceField.setEditable(false);

        SyncIntervalOption[] options = {
                new SyncIntervalOption("关闭自动检查", 0), new SyncIntervalOption("每 1 分钟", 1),
                new SyncIntervalOption("每 2 分钟", 2), new SyncIntervalOption("每 5 分钟", 5),
                new SyncIntervalOption("每 10 分钟", 10), new SyncIntervalOption("每 15 分钟", 15),
                new SyncIntervalOption("每 30 分钟", 30), new SyncIntervalOption("每 60 分钟", 60)};
        JComboBox<SyncIntervalOption> intervalBox = new JComboBox<>(options);
        for (SyncIntervalOption option : options) if (option.minutes == config.syncIntervalMinutes) intervalBox.setSelectedItem(option);

        JPanel cloudPanel = new JPanel(new BorderLayout(0, 12));
        cloudPanel.setBackground(Color.WHITE);
        cloudPanel.setBorder(new EmptyBorder(18, 22, 18, 22));
        JPanel cloudForm = new JPanel(new GridBagLayout());
        cloudForm.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(7, 5, 7, 5); c.fill = GridBagConstraints.HORIZONTAL;
        addSettingRow(cloudForm, c, 0, "云同步地址", endpointField, null);
        addSettingRow(cloudForm, c, 1, "同步空间", spaceField, null);
        addSettingRow(cloudForm, c, 2, "同步空间密码", passwordField, null);
        addSettingRow(cloudForm, c, 3, "自动检查间隔", intervalBox, null);
        JLabel signalHint = new JLabel("自动检查只读取一个很小的云端更新信号；信号未变化时不会下载检查数据。", SwingConstants.LEFT);
        signalHint.setForeground(MUTED); signalHint.setFont(signalHint.getFont().deriveFont(12f));
        c.gridy=4;c.gridx=1;c.weightx=1;cloudForm.add(signalHint,c);
        cloudPanel.add(cloudForm, BorderLayout.NORTH);

        JTextArea testLogArea = new JTextArea("这里显示最近一次“测试连接”的网络诊断日志。", 12, 72);
        testLogArea.setEditable(false); testLogArea.setLineWrap(true); testLogArea.setWrapStyleWord(true);
        testLogArea.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        JScrollPane testLogScroll = new JScrollPane(testLogArea);
        testLogScroll.setBorder(BorderFactory.createTitledBorder("连接测试 / 网络日志"));
        cloudPanel.add(testLogScroll, BorderLayout.CENTER);
        JLabel saveState = new JLabel(" "); saveState.setForeground(SUCCESS);
        JButton saveCloud = actionButton("保存设置", true);
        JButton test = actionButton("测试连接", false);
        test.setPreferredSize(new Dimension(118,36));
        saveCloud.addActionListener(e -> {
            try {
                saveSettings(endpointField, spaceField, passwordField, archiveField, shiftField, intervalBox);
                saveState.setText("设置已保存 · " + now());
            } catch (Exception error) { showError("保存设置失败", error); }
        });
        test.addActionListener(e -> testConnection(endpointField.getText().trim(), spaceField.getText().trim(), new String(passwordField.getPassword()), testLogArea));
        JPanel cloudActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); cloudActions.setOpaque(false);
        cloudActions.add(saveState); cloudActions.add(test); cloudActions.add(saveCloud); cloudPanel.add(cloudActions, BorderLayout.SOUTH);

        JPanel dataPanel = new JPanel(new BorderLayout(0, 14)); dataPanel.setBackground(Color.WHITE); dataPanel.setBorder(new EmptyBorder(20,22,20,22));
        JPanel storageForm = new JPanel(new GridBagLayout()); storageForm.setOpaque(false);
        GridBagConstraints d = new GridBagConstraints(); d.insets=new Insets(7,5,7,5);d.fill=GridBagConstraints.HORIZONTAL;
        JButton choose = actionButton("选择文件夹", false);
        choose.addActionListener(e -> { JFileChooser chooser=new JFileChooser(archiveField.getText());chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(chooser.showOpenDialog(dialog)==JFileChooser.APPROVE_OPTION)archiveField.setText(chooser.getSelectedFile().toPath().toString()); });
        addSettingRow(storageForm,d,0,"电脑本地资料库",archiveField,choose);
        addSettingRow(storageForm,d,1,"本机设备名称",deviceField,null);
        addSettingRow(storageForm,d,2,"倒班日期",shiftField,null);
        JLabel dataHint=new JLabel("检查记录长期保存在电脑资料库中；.safetydata 用于 Android/PC 之间手动导入导出。",SwingConstants.LEFT);dataHint.setForeground(MUTED);dataHint.setFont(dataHint.getFont().deriveFont(12f));
        d.gridy=3;d.gridx=1;d.weightx=1;storageForm.add(dataHint,d);dataPanel.add(storageForm,BorderLayout.NORTH);
        JPanel dataButtons=new JPanel(new GridLayout(2,2,12,12));dataButtons.setOpaque(false);dataButtons.setBorder(new EmptyBorder(10,40,10,40));
        JButton openArchive=actionButton("打开本地资料库",false);JButton importData=actionButton("导入检查数据",false);JButton exportData=actionButton("导出检查数据",false);JButton saveData=actionButton("保存存储设置",true);
        openArchive.addActionListener(e->openArchive());importData.addActionListener(e->importPackage());exportData.addActionListener(e->exportPortable());
        saveData.addActionListener(e->{try{saveSettings(endpointField,spaceField,passwordField,archiveField,shiftField,intervalBox);}catch(Exception error){showError("保存设置失败",error);}});
        dataButtons.add(openArchive);dataButtons.add(importData);dataButtons.add(exportData);dataButtons.add(saveData);dataPanel.add(dataButtons,BorderLayout.CENTER);

        JPanel logPanel=new JPanel(new BorderLayout(0,14));logPanel.setBackground(Color.WHITE);logPanel.setBorder(new EmptyBorder(20,22,20,22));
        JTextArea logInfo=new JTextArea("网络/同步日志：记录 DNS、系统代理、HTTP 请求阶段、状态码和同步步骤，不记录同步密码。\n\n软件崩溃日志：仅在 PC 客户端发生未捕获异常时生成，用于排查闪退或窗口异常。平时不会产生无意义日志。",5,70);
        logInfo.setEditable(false);logInfo.setOpaque(false);logInfo.setLineWrap(true);logInfo.setWrapStyleWord(true);logInfo.setForeground(MUTED);logPanel.add(logInfo,BorderLayout.NORTH);
        JPanel logButtons=new JPanel(new GridLayout(3,2,12,12));logButtons.setOpaque(false);logButtons.setBorder(new EmptyBorder(18,60,18,60));
        JButton viewSync=actionButton("查看网络/同步日志",false);JButton exportSync=actionButton("导出网络/同步日志",false);JButton viewCrash=actionButton("查看软件崩溃日志",false);JButton exportCrash=actionButton("导出软件崩溃日志",false);JButton openLogs=actionButton("打开日志文件夹",false);JButton clearLabel=actionButton("说明：日志自动清理",false);clearLabel.setEnabled(false);
        viewSync.addActionListener(e->viewSyncLog());exportSync.addActionListener(e->exportSyncLog());viewCrash.addActionListener(e->viewCrashLog());exportCrash.addActionListener(e->exportCrashLog());openLogs.addActionListener(e->openLogFolder());
        logButtons.add(viewSync);logButtons.add(exportSync);logButtons.add(viewCrash);logButtons.add(exportCrash);logButtons.add(openLogs);logButtons.add(clearLabel);logPanel.add(logButtons,BorderLayout.CENTER);

        JTabbedPane tabs=new JTabbedPane();tabs.addTab("云同步",cloudPanel);tabs.addTab("数据与资料",dataPanel);tabs.addTab("日志与诊断",logPanel);
        tabs.setBorder(new EmptyBorder(8,10,4,10)); dialog.add(tabs,BorderLayout.CENTER);
        JButton close=actionButton("关闭",false);close.setPreferredSize(new Dimension(92,36));close.addActionListener(e->dialog.dispose());
        JPanel bottom=new JPanel(new FlowLayout(FlowLayout.RIGHT,10,8));bottom.setBackground(BG);bottom.add(close);dialog.add(bottom,BorderLayout.SOUTH);
        dialog.setSize(920,640);dialog.setMinimumSize(new Dimension(820,560));dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }

    private void saveSettings(JTextField endpointField,JTextField spaceField,JPasswordField passwordField,JTextField archiveField,
                              JTextField shiftField,JComboBox<SyncIntervalOption> intervalBox)throws Exception{
        config.endpoint=endpointField.getText().trim();config.space=spaceField.getText().trim();if(config.space.isBlank())config.space="safety-ledger";
        config.password=new String(passwordField.getPassword());config.archiveRoot=archiveField.getText().trim();config.shiftDates=shiftField.getText().trim();
        SyncIntervalOption option=(SyncIntervalOption)intervalBox.getSelectedItem();config.syncIntervalMinutes=option==null?5:option.minutes;
        if(config.archiveRoot.isBlank())throw new IllegalArgumentException("请选择电脑本地资料库文件夹");
        Files.createDirectories(config.archivePath());config.save();holidayService=new HolidayCalendarService(config.privateDir());CrashLogger.install(config.privateDir());
        lastCloudRevision="";scheduleAutoSync();selectedIds.clear();refreshTable();migrateWordLayoutAsync();setStatus("设置已保存 · 自动检查间隔："+(config.syncIntervalMinutes==0?"关闭":config.syncIntervalMinutes+" 分钟"));
    }
'''
s = sub_once(s, r'    private void showSettings\(\) \{.*?\n    private static void addSettingRow', settings_method + '\n    private static void addSettingRow', 'settings method')

schedule_methods = r'''
    private synchronized void scheduleAutoSync() {
        if (autoSyncFuture != null) autoSyncFuture.cancel(false);
        autoSyncFuture = null;
        if (config.syncIntervalMinutes <= 0 || config.endpoint.isBlank() || config.password.isBlank()) return;
        long interval = Math.max(1, config.syncIntervalMinutes);
        autoSyncFuture = scheduler.scheduleWithFixedDelay(this::pollCloudSignal, 15, interval * 60L, TimeUnit.SECONDS);
    }

    private void pollCloudSignal() {
        if (syncing || config.endpoint.isBlank() || config.password.isBlank()) return;
        try {
            CloudClient client = new CloudClient(config.endpoint, config.space, config.password.toCharArray());
            String revision = client.revision();
            if (revision.isBlank()) { sync(false); return; }
            if (lastCloudRevision.isBlank() || !revision.equals(lastCloudRevision)) {
                SwingUtilities.invokeLater(() -> setStatus("检测到云端更新，正在同步…"));
                sync(false);
            } else {
                SwingUtilities.invokeLater(() -> setStatus("云端无更新 · " + now()));
            }
        } catch (Exception error) {
            SyncLogger logger = newSyncLogger();
            if (logger != null) { logger.log("自动更新信号检查失败"); logger.error("自动更新信号检查", error); }
            SwingUtilities.invokeLater(() -> setStatus("自动检查云端失败（已记录日志）：" + friendlyError(error)));
        }
    }

'''
s = replace_once(s, '    private SyncLogger newSyncLogger() {', schedule_methods + '    private SyncLogger newSyncLogger() {', 'schedule methods insert')

crash_methods = r'''
    private Path currentCrashLog(){return CrashLogger.latest(config.privateDir());}
    private void viewCrashLog(){Path log=currentCrashLog();JTextArea area=new JTextArea(CrashLogger.readTail(log,160000),30,105);area.setEditable(false);area.setLineWrap(false);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));JOptionPane.showMessageDialog(this,new JScrollPane(area),log==null?"软件崩溃日志":"软件崩溃日志 · "+log.getFileName(),JOptionPane.PLAIN_MESSAGE);}
    private void exportCrashLog(){Path log=currentCrashLog();if(log==null||!Files.isRegularFile(log)){JOptionPane.showMessageDialog(this,"暂无软件崩溃日志。只有发生未捕获异常/闪退时才会生成。","提示",JOptionPane.INFORMATION_MESSAGE);return;}JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("安全检查台账-PC-崩溃日志-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".log"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Files.copy(log,chooser.getSelectedFile().toPath(),StandardCopyOption.REPLACE_EXISTING);}catch(Exception error){showError("导出崩溃日志失败",error);}}
    private void openLogFolder(){try{Path dir=config.privateDir().resolve("logs");Files.createDirectories(dir);Desktop.getDesktop().open(dir.toFile());}catch(Exception error){showError("打开日志文件夹失败",error);}}

'''
s = replace_once(s, '    private void showSyncError(String title, Exception error, SyncLogger logger) {', crash_methods + '    private void showSyncError(String title, Exception error, SyncLogger logger) {', 'crash methods insert')

test_method = r'''
    private void testConnection(String endpoint, String space, String password, JTextArea output) {
        if (endpoint.isBlank() || password.isBlank()) {
            output.setText("请先填写云同步地址和同步空间密码。"); return;
        }
        SyncLogger logger = newSyncLogger();
        output.setText("正在测试连接……\n将检查 DNS、Windows 系统代理、/health、WebDAV 读写和设备登记。\n");
        setStatus("正在测试云端连接…");
        CompletableFuture.runAsync(() -> {
            try {
                if (logger != null) logger.log("测试连接开始 · endpoint=" + endpoint + " · space=" + space);
                CloudClient client = new CloudClient(endpoint, space, password.toCharArray(), logger);
                client.testReadWrite();
                if (client.isDeviceLoggedOut(config.deviceId)) throw new SecurityException("此电脑已被管理员登出；请先在管理员手机中允许该设备重新加入");
                client.registerPcDevice(config.deviceId, config.deviceName);
                String revision = client.revision();
                if (logger != null) logger.log("测试连接全部完成 · signal=" + (revision.isBlank()?"兼容模式":revision));
                SwingUtilities.invokeLater(() -> { setStatus("连接成功 · " + now()); output.setText(SyncLogger.readTail(logger==null?null:logger.file(),50000)); output.setCaretPosition(output.getDocument().getLength()); });
            } catch (Exception error) {
                if (logger != null) logger.error("测试连接失败", error);
                SwingUtilities.invokeLater(() -> { setStatus("测试连接失败：" + friendlyError(error)); output.setText(SyncLogger.readTail(logger==null?null:logger.file(),50000)+"\n\n用户提示："+friendlyError(error)); output.setCaretPosition(output.getDocument().getLength()); });
            }
        });
    }

'''
s = sub_once(s, r'    private void testConnection\(\) \{.*?\n    private void sync\(boolean manual\) \{', test_method + '    private void sync(boolean manual) {', 'test connection')
s = replace_once(s,
'''                if (logger != null) logger.log("同步结束 · changed=" + changed + " · records=" + records + " · failed=" + failed);''',
'''                try { String revision = client.revision(); if (!revision.isBlank()) lastCloudRevision = revision; } catch (Exception signalError) { if (logger != null) logger.error("同步完成后读取更新信号", signalError); }\n                if (logger != null) logger.log("同步结束 · changed=" + changed + " · records=" + records + " · failed=" + failed);''', 'sync revision')
s = replace_once(s,
'''            } catch (Exception error) {\n                SwingUtilities.invokeLater(() -> showSyncError("同步失败", error, logger));\n            } finally {''',
'''            } catch (Exception error) {\n                if (manual) SwingUtilities.invokeLater(() -> showSyncError("同步失败", error, logger));\n                else { if (logger != null) logger.error("后台同步失败", error); SwingUtilities.invokeLater(() -> setStatus("后台同步失败（已记录日志）：" + friendlyError(error))); }\n            } finally {''', 'background error')
s = s.replace('设置 → 资料库 / 数据工具 / 日志', '设置 → 日志与诊断')
s = s.replace('"网络连接问题：暂时无法连接云同步服务器。请检查电脑网络、VPN/代理和云同步地址后重试；电脑本地资料不会因此丢失。"', '"网络连接问题：暂时无法连接云同步服务器。PC 0.2.3 已启用 Windows 系统代理；请在“设置 → 云同步 → 测试连接”查看 DNS/代理/HTTP 诊断日志。电脑本地资料不会因此丢失。"')

main_repl = r'''
    private static void installUiDefaults(){
        String[] preferred={"Microsoft YaHei UI","Microsoft YaHei","Noto Sans CJK SC","Segoe UI","Dialog"};
        Set<String> available=new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        String family="Dialog";for(String f:preferred)if(available.contains(f)){family=f;break;}
        Font normal=new Font(family,Font.PLAIN,14),bold=new Font(family,Font.BOLD,14);
        String[] normalKeys={"Label.font","Button.font","ComboBox.font","TextField.font","PasswordField.font","TabbedPane.font","OptionPane.font","Menu.font","MenuItem.font","CheckBox.font","RadioButton.font","ToolTip.font","Table.font"};
        for(String key:normalKeys)UIManager.put(key,normal);UIManager.put("TableHeader.font",bold);UIManager.put("TitledBorder.font",bold);
        UIManager.put("control",Color.WHITE);UIManager.put("Table.selectionBackground",new Color(224,237,255));UIManager.put("Table.selectionForeground",new Color(24,42,67));
    }

    private static final class SyncIntervalOption { final String label; final int minutes; SyncIntervalOption(String label,int minutes){this.label=label;this.minutes=minutes;} @Override public String toString(){return label;} }

    public static void main(String[]args){
        System.setProperty("java.net.useSystemProxies","true");System.setProperty("awt.useSystemAAFontSettings","lcd");System.setProperty("swing.aatext","true");
        System.setProperty("sun.java2d.d3d","false");System.setProperty("sun.java2d.opengl","false");
        SwingUtilities.invokeLater(()->{try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}installUiDefaults();new SafetyLedgerDesktop().setVisible(true);});
    }
'''
s = sub_once(s, r'    public static void main\(String\[\]args\)\{.*?\n    \}\n\}', main_repl + '}', 'main ui defaults')
p.write_text(s, encoding="utf-8")

pom = (ROOT / "desktop/pom.xml").read_text(encoding="utf-8").replace('<version>0.2.2</version>', '<version>0.2.3</version>', 1).replace('safety-ledger-pc-0.2.2-all', 'safety-ledger-pc-0.2.3-all')
write("desktop/pom.xml", pom)
write("desktop/VERSION_0.2.3.txt", '''安全检查台账 PC 0.2.3\n\n- Windows 字体与 DPI 清晰度优化，按钮采用稳定的自绘基础 UI。\n- 左上角应用图标重新绘制，并提供 16/20/24/32/48/64/128/256 多尺寸图标。\n- 设置重构为“云同步 / 数据与资料 / 日志与诊断”。\n- 云同步支持可选自动检查间隔；平时只轮询轻量更新信号，有变化才完整同步。\n- 测试连接直接显示 DNS、系统代理、HTTP 与 WebDAV 网络日志。\n- 新增软件崩溃日志，仅在未捕获异常时生成。\n- Java HttpClient 启用 Windows 系统代理并固定 HTTP/1.1，改善 workers.dev + VPN/代理环境连接。\n- Cloudflare Worker 同时支持 R2 与现有 D1(DB) 绑定，并提供轻量同步信号。''')

worker = r'''
/**
 * Safety Ledger WebDAV-compatible Cloudflare Worker v2.
 * Storage priority: R2 binding SAFETY_LEDGER_BUCKET; fallback: existing D1 binding DB.
 * D1 fallback stores binary objects in 256 KiB chunks so the user's existing DB deployment can be reused.
 */
const CHUNK_SIZE = 256 * 1024;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method.toUpperCase();
    const backend = env.SAFETY_LEDGER_BUCKET ? 'R2' : env.DB ? 'D1' : null;

    if (url.pathname === '/health' && method === 'GET') {
      return json({
        ok: Boolean(backend), service: 'Safety Ledger Sync', protocol: 'safety-ledger-webdav-v2',
        storage: backend, signal: true,
        binding: backend === 'R2' ? 'SAFETY_LEDGER_BUCKET' : backend === 'D1' ? 'DB' : null,
        error: backend ? null : '未检测到存储绑定：可绑定现有 D1 为 DB，或绑定 R2 为 SAFETY_LEDGER_BUCKET',
      }, backend ? 200 : 503);
    }
    if (!backend) return json({ ok:false, error:'云端部署不完整：需要 D1(DB) 或 R2(SAFETY_LEDGER_BUCKET) 存储绑定。' }, 503);

    const key = decodeURIComponent(url.pathname.replace(/^\/+/, ''));
    if (!(await authorized(request, env, key))) return json({ ok:false, error:'需要设备授权' }, 401, { 'WWW-Authenticate':'SafetyLedger realm="Safety Ledger"' });
    const common = { DAV:'1', 'Cache-Control':'no-store', 'X-Safety-Ledger-Protocol':'safety-ledger-webdav-v2' };

    if (method === 'OPTIONS') return new Response(null, { status:204, headers:{ ...common, Allow:'OPTIONS, PROPFIND, MKCOL, PUT, GET, HEAD, DELETE' } });
    if (key.endsWith('/.sync-signal') && method === 'GET') {
      const space = firstSegment(key);
      return json({ ok:true, revision:await getRevision(env, space) }, 200, common);
    }
    if (method === 'MKCOL') return new Response(null, { status:201, headers:common });
    if (method === 'PUT') {
      if (!key) return new Response('Path required', { status:400, headers:common });
      const bytes = new Uint8Array(await request.arrayBuffer());
      const contentType = request.headers.get('content-type') || 'application/octet-stream';
      await putObject(env, key, bytes, contentType);
      if (isContentKey(key)) await bumpRevision(env, firstSegment(key));
      return new Response(null, { status:201, headers:common });
    }
    if (method === 'GET' || method === 'HEAD') {
      const object = await getObject(env, key);
      if (!object) return new Response('Not found', { status:404, headers:common });
      const headers = new Headers(common); headers.set('etag', object.etag); headers.set('content-length', String(object.size));
      if (object.contentType) headers.set('content-type', object.contentType);
      if (object.updatedAt) headers.set('last-modified', new Date(object.updatedAt).toUTCString());
      return new Response(method === 'HEAD' ? null : object.bytes, { status:200, headers });
    }
    if (method === 'DELETE') {
      await deleteObject(env, key); if (isContentKey(key)) await bumpRevision(env, firstSegment(key));
      return new Response(null, { status:204, headers:common });
    }
    if (method === 'PROPFIND') {
      const depth=request.headers.get('depth')||'0';const normalized=key&&!key.endsWith('/')?`${key}/`:key;
      const entries=[{key:normalized,directory:true,size:0}];
      if(depth!=='0') entries.push(...await listObjects(env,normalized));
      const xml=`<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">${entries.map(e=>responseXml(e)).join('')}</d:multistatus>`;
      return new Response(xml,{status:207,headers:{...common,'Content-Type':'application/xml; charset=utf-8'}});
    }
    return new Response('Method not allowed',{status:405,headers:common});
  },
};

async function authorized(request, env, key) {
  const header=request.headers.get('authorization')||'';
  if(env.SYNC_TOKEN&&header===`Bearer ${env.SYNC_TOKEN}`)return true;
  if(env.SYNC_USERNAME&&env.SYNC_PASSWORD&&header===`Basic ${btoa(`${env.SYNC_USERNAME}:${env.SYNC_PASSWORD}`)}`)return true;
  if(env.DISABLE_SELF_PROVISION==='true')return false;
  const space=request.headers.get('x-safety-ledger-space')||'',prefix='SafetyLedger ';
  if(!space||space==='_safety_auth'||!header.startsWith(prefix))return false;
  const proof=header.slice(prefix.length);if(!/^[A-Za-z0-9_-]{43}$/.test(proof))return false;
  const first=firstSegment(key);if(first&&first!==space)return false;
  const authId=`auth:${await sha256Url(space)}`;const existing=await metaGet(env,authId);
  if(!existing){await metaSet(env,authId,proof);return true;}return constantTimeEqual(existing.trim(),proof);
}

function isContentKey(key){return /(^|\/)devices\/.*\.safetydata$/i.test(key)&&!key.includes('.safety-pc-probe-');}
function firstSegment(key){return key.split('/').filter(Boolean)[0]||'';}
async function getRevision(env,space){return (await metaGet(env,`rev:${space}`))||'0';}
async function bumpRevision(env,space){if(space)await metaSet(env,`rev:${space}`,`${Date.now()}-${crypto.randomUUID()}`);}

async function ensureD1(db){
  await db.batch([
    db.prepare('CREATE TABLE IF NOT EXISTS dav_objects (key TEXT PRIMARY KEY, size INTEGER NOT NULL, etag TEXT NOT NULL, content_type TEXT, updated_at INTEGER NOT NULL, chunks INTEGER NOT NULL)'),
    db.prepare('CREATE TABLE IF NOT EXISTS dav_chunks (key TEXT NOT NULL, idx INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(key, idx))'),
    db.prepare('CREATE TABLE IF NOT EXISTS dav_meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)')
  ]);
}

async function metaGet(env,name){
  if(env.SAFETY_LEDGER_BUCKET){const o=await env.SAFETY_LEDGER_BUCKET.get(`_safety_meta/${await sha256Url(name)}.txt`);return o?await o.text():null;}
  await ensureD1(env.DB);const row=await env.DB.prepare('SELECT value FROM dav_meta WHERE name=?').bind(name).first();return row?String(row.value):null;
}
async function metaSet(env,name,value){
  if(env.SAFETY_LEDGER_BUCKET){await env.SAFETY_LEDGER_BUCKET.put(`_safety_meta/${await sha256Url(name)}.txt`,String(value));return;}
  await ensureD1(env.DB);await env.DB.prepare('INSERT INTO dav_meta(name,value) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value').bind(name,String(value)).run();
}

async function putObject(env,key,bytes,contentType){
  if(env.SAFETY_LEDGER_BUCKET){await env.SAFETY_LEDGER_BUCKET.put(key,bytes,{httpMetadata:{contentType}});return;}
  await ensureD1(env.DB);const etag=`"${await sha256UrlBytes(bytes)}"`,updated=Date.now(),chunks=Math.ceil(bytes.length/CHUNK_SIZE);
  const statements=[env.DB.prepare('DELETE FROM dav_chunks WHERE key=?').bind(key),env.DB.prepare('DELETE FROM dav_objects WHERE key=?').bind(key)];
  for(let i=0;i<chunks;i++){const part=bytes.slice(i*CHUNK_SIZE,Math.min(bytes.length,(i+1)*CHUNK_SIZE));statements.push(env.DB.prepare('INSERT INTO dav_chunks(key,idx,data) VALUES(?,?,?)').bind(key,i,part.buffer));}
  statements.push(env.DB.prepare('INSERT INTO dav_objects(key,size,etag,content_type,updated_at,chunks) VALUES(?,?,?,?,?,?)').bind(key,bytes.length,etag,contentType,updated,chunks));
  await env.DB.batch(statements);
}
async function getObject(env,key){
  if(env.SAFETY_LEDGER_BUCKET){const o=await env.SAFETY_LEDGER_BUCKET.get(key);if(!o)return null;const bytes=await o.arrayBuffer();return{bytes,size:o.size,etag:o.httpEtag,contentType:o.httpMetadata?.contentType||'application/octet-stream',updatedAt:o.uploaded?o.uploaded.getTime():Date.now()};}
  await ensureD1(env.DB);const meta=await env.DB.prepare('SELECT size,etag,content_type,updated_at,chunks FROM dav_objects WHERE key=?').bind(key).first();if(!meta)return null;
  const rows=(await env.DB.prepare('SELECT idx,data FROM dav_chunks WHERE key=? ORDER BY idx').bind(key).all()).results||[];const out=new Uint8Array(Number(meta.size));let offset=0;
  for(const row of rows){let part;if(row.data instanceof ArrayBuffer)part=new Uint8Array(row.data);else if(ArrayBuffer.isView(row.data))part=new Uint8Array(row.data.buffer);else part=new Uint8Array(row.data||[]);out.set(part,offset);offset+=part.length;}
  return{bytes:out,size:Number(meta.size),etag:String(meta.etag),contentType:String(meta.content_type||'application/octet-stream'),updatedAt:Number(meta.updated_at)};
}
async function deleteObject(env,key){
  if(env.SAFETY_LEDGER_BUCKET){await env.SAFETY_LEDGER_BUCKET.delete(key);return;}
  await ensureD1(env.DB);await env.DB.batch([env.DB.prepare('DELETE FROM dav_chunks WHERE key=?').bind(key),env.DB.prepare('DELETE FROM dav_objects WHERE key=?').bind(key)]);
}
async function listObjects(env,prefix){
  if(env.SAFETY_LEDGER_BUCKET){const out=[];let cursor;do{const page=await env.SAFETY_LEDGER_BUCKET.list({prefix,cursor});for(const o of page.objects)if(!o.key.startsWith('_safety_'))out.push({key:o.key,directory:false,size:o.size});cursor=page.truncated?page.cursor:undefined;}while(cursor);return out;}
  await ensureD1(env.DB);const rows=(await env.DB.prepare('SELECT key,size FROM dav_objects WHERE substr(key,1,?)=? ORDER BY key').bind(prefix.length,prefix).all()).results||[];return rows.map(r=>({key:String(r.key),directory:false,size:Number(r.size)}));
}

function responseXml(entry){const path='/'+entry.key.split('/').filter(Boolean).map(encodeURIComponent).join('/')+(entry.directory?'/':'');return `<d:response><d:href>${escapeXml(path)}</d:href><d:propstat><d:prop><d:displayname>${escapeXml(entry.key.split('/').filter(Boolean).pop()||'root')}</d:displayname>${entry.directory?'<d:resourcetype><d:collection/></d:resourcetype>':`<d:resourcetype/><d:getcontentlength>${entry.size}</d:getcontentlength>`}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`;}
function json(value,status=200,extra={}){return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store',...extra}});}
async function sha256Url(value){return sha256UrlBytes(new TextEncoder().encode(value));}
async function sha256UrlBytes(bytes){const digest=await crypto.subtle.digest('SHA-256',bytes);let binary='';for(const b of new Uint8Array(digest))binary+=String.fromCharCode(b);return btoa(binary).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');}
function constantTimeEqual(left,right){if(left.length!==right.length)return false;let d=0;for(let i=0;i<left.length;i++)d|=left.charCodeAt(i)^right.charCodeAt(i);return d===0;}
function escapeXml(value){return value.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');}
'''
write("cloudflare-worker/worker.js", worker)
write("cloudflare-worker/wrangler.toml.example", '''name = "safety-inspection-ledger-cloud"\nmain = "worker.js"\ncompatibility_date = "2026-08-16"\n\n# 现有部署可直接继续使用 D1。把 database_id 换成你当前 safety-inspection-ledger 的 D1 ID。\n[[d1_databases]]\nbinding = "DB"\ndatabase_name = "safety-inspection-ledger"\ndatabase_id = "REPLACE_WITH_EXISTING_D1_DATABASE_ID"\n\n# 如果以后改用 R2，只需绑定 bucket 为 SAFETY_LEDGER_BUCKET；Worker 会自动优先使用 R2。\n# [[r2_buckets]]\n# binding = "SAFETY_LEDGER_BUCKET"\n# bucket_name = "safety-inspection-ledger"''')

wf = ROOT / ".github/workflows/pc-v022-windows.yml"
if wf.exists():
    w = wf.read_text(encoding="utf-8")
    w = w.replace('PC 0.2.2 Windows Build','PC 0.2.3 Windows Build').replace('0.2.2','0.2.3')
    w = w.replace('--java-options "-Dfile.encoding=UTF-8" `', '--java-options "-Dfile.encoding=UTF-8" `\n            --java-options "-Djava.net.useSystemProxies=true" `\n            --java-options "-Dawt.useSystemAAFontSettings=lcd" `\n            --java-options "-Dswing.aatext=true" `\n            --java-options "-Dsun.java2d.d3d=false" `')
    w = w.replace('0.2.2 主要更新：', '0.2.3 主要更新：')
    wf.write_text(w, encoding="utf-8")

print('PC 0.2.3 patch applied')
