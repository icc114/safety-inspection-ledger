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
