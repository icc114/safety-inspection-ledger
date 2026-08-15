import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

import javax.crypto.*;
import javax.crypto.spec.*;

/** Cross-platform reader/writer for Android portable .safetydata files. JDK 17 only. */
public final class SafetyDataTool {
    private static final byte[] MAGIC="SAFETYLOCAL2".getBytes(StandardCharsets.US_ASCII);
    private static final char[] KEY="safety-ledger-portable-backup-v2".toCharArray();
    private static final int ITERATIONS=310_000,CHUNK_SIZE=256*1024,TAG_BYTES=16;
    private SafetyDataTool(){}

    public static void main(String[]args)throws Exception{
        if(args.length<2)usage();
        switch(args[0]){
            case "info"->inspect(Path.of(args[1]),false);
            case "verify"->inspect(Path.of(args[1]),true);
            case "extract"->{if(args.length!=3)usage();extract(Path.of(args[1]),Path.of(args[2]));}
            case "pack"->{if(args.length!=3)usage();pack(Path.of(args[1]),Path.of(args[2]));}
            default->usage();
        }
    }

    private static void inspect(Path source,boolean only)throws Exception{
        Path zip=decrypt(source);try{Properties m=verifyZip(zip);if(only){System.out.println("OK: Android/PC 数据包、AES-GCM 和数据库 SHA-256 校验通过");return;}
            System.out.println("format="+m.getProperty("format"));System.out.println("formatVersion="+m.getProperty("formatVersion"));System.out.println("schemaVersion="+m.getProperty("schemaVersion"));
            String created=m.getProperty("createdAt","0");try{System.out.println("createdAt="+Instant.ofEpochMilli(Long.parseLong(created)));}catch(Exception e){System.out.println("createdAt="+created);}System.out.println("databaseSha256="+m.getProperty("databaseSha256"));
            try(ZipFile z=new ZipFile(zip.toFile())){System.out.println("mediaFiles="+z.stream().filter(e->!e.isDirectory()&&e.getName().startsWith("business_media/")).count());}
        }finally{Files.deleteIfExists(zip);}
    }

    private static void extract(Path source,Path destination)throws Exception{
        Path zip=decrypt(source);try{verifyZip(zip);Files.createDirectories(destination);Path root=destination.toAbsolutePath().normalize();
            try(ZipInputStream in=new ZipInputStream(Files.newInputStream(zip))){for(ZipEntry e;(e=in.getNextEntry())!=null;){Path target=root.resolve(e.getName()).normalize();if(!target.startsWith(root))throw new IOException("数据包包含非法路径");if(e.isDirectory())Files.createDirectories(target);else{Files.createDirectories(target.getParent());Files.copy(in,target,StandardCopyOption.REPLACE_EXISTING);}}}System.out.println("OK: 已解包到 "+root);
        }finally{Files.deleteIfExists(zip);}
    }

    private static void pack(Path sourceDirectory,Path destination)throws Exception{
        Path source=sourceDirectory.toAbsolutePath().normalize(),db=source.resolve("database.sqlite");if(!Files.isRegularFile(db))throw new IOException("目录中缺少 database.sqlite");
        Properties m=new Properties();Path existing=source.resolve("manifest.properties");if(Files.isRegularFile(existing))try(InputStream in=Files.newInputStream(existing)){m.load(in);}m.setProperty("format","safetydata");m.setProperty("formatVersion","1");m.putIfAbsent("schemaVersion","0");m.setProperty("createdAt",String.valueOf(System.currentTimeMillis()));m.setProperty("databaseSha256",sha256(db));
        Path zip=Files.createTempFile("safetydata-pc-pack-",".zip");try{try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))){out.putNextEntry(new ZipEntry("manifest.properties"));m.store(out,"Safety Ledger portable backup");out.closeEntry();addFile(out,db,"database.sqlite");Path media=source.resolve("business_media");if(Files.isDirectory(media))try(var paths=Files.walk(media)){for(Path p:paths.filter(Files::isRegularFile).sorted().toList())addFile(out,p,"business_media/"+media.relativize(p).toString().replace('\\','/'));}}
            encryptV2(zip,destination);System.out.println("OK: 已生成 Android/PC v2 数据包 "+destination.toAbsolutePath());}finally{Files.deleteIfExists(zip);}
    }

    private static Path decrypt(Path source)throws Exception{
        if(!Files.isRegularFile(source))throw new IOException("找不到数据包："+source);Path zip=Files.createTempFile("safetydata-pc-read-",".zip");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(source)));OutputStream out=new BufferedOutputStream(Files.newOutputStream(zip))){byte[] magic=in.readNBytes(MAGIC.length);if(!Arrays.equals(magic,MAGIC))throw new IOException("不是便携 .safetydata 数据包");int version=in.readUnsignedByte();if(version<1||version>2)throw new IOException("不支持的数据包版本："+version);byte[]salt=in.readNBytes(16),nonce=in.readNBytes(12);if(salt.length!=16||nonce.length!=12)throw new EOFException("数据包头不完整");SecretKey key=derive(salt);
            if(version==1){Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));try(CipherInputStream ci=new CipherInputStream(in,c)){ci.transferTo(out);}catch(IOException e){throw new IOException("AES-GCM 完整性校验失败",e);}return zip;}
            long counter=0;while(true){int plain;try{plain=in.readInt();}catch(EOFException e){throw new EOFException("数据包被截断");}if(plain==0)break;if(plain<0||plain>CHUNK_SIZE)throw new IOException("数据包分块长度非法");byte[]enc=in.readNBytes(plain+TAG_BYTES);if(enc.length!=plain+TAG_BYTES)throw new EOFException("数据包分块被截断");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,chunkNonce(nonce,counter)));c.updateAAD(chunkAad(counter,plain));try{out.write(c.doFinal(enc));}catch(Exception e){throw new IOException("AES-GCM 完整性校验失败",e);}counter++;}return zip;
        }catch(Exception e){Files.deleteIfExists(zip);throw e;}
    }

    private static void encryptV2(Path zip,Path destination)throws Exception{
        byte[]salt=random(16),nonce=random(12);SecretKey key=derive(salt);Path parent=destination.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
        try(OutputStream raw=new BufferedOutputStream(Files.newOutputStream(destination));DataOutputStream data=new DataOutputStream(raw);InputStream in=new BufferedInputStream(Files.newInputStream(zip))){raw.write(MAGIC);raw.write(2);raw.write(salt);raw.write(nonce);byte[]buffer=new byte[CHUNK_SIZE];long counter=0;for(int n;(n=in.read(buffer))>=0;){if(n==0)continue;Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,chunkNonce(nonce,counter)));c.updateAAD(chunkAad(counter,n));byte[]enc=c.doFinal(buffer,0,n);data.writeInt(n);data.write(enc);counter++;}data.writeInt(0);data.flush();}
    }

    private static byte[]chunkNonce(byte[]base,long counter){byte[]n=base.clone();long seed=ByteBuffer.wrap(base,4,8).getLong();ByteBuffer.wrap(n,4,8).putLong(seed+counter);return n;}
    private static byte[]chunkAad(long counter,int plain)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();try(DataOutputStream d=new DataOutputStream(b)){d.write(MAGIC);d.writeLong(counter);d.writeInt(plain);}return b.toByteArray();}
    private static byte[]random(int n){byte[]b=new byte[n];new SecureRandom().nextBytes(b);return b;}
    private static Properties verifyZip(Path zip)throws Exception{try(ZipFile z=new ZipFile(zip.toFile())){ZipEntry me=z.getEntry("manifest.properties"),de=z.getEntry("database.sqlite");if(me==null||de==null)throw new IOException("数据包缺少 manifest.properties 或 database.sqlite");Properties m=new Properties();try(InputStream in=z.getInputStream(me)){m.load(in);}if(!"safetydata".equals(m.getProperty("format")))throw new IOException("manifest 格式标识错误");MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=z.getInputStream(de)){byte[]buf=new byte[65536];for(int n;(n=in.read(buf))>0;)d.update(buf,0,n);}if(!HexFormat.of().formatHex(d.digest()).equalsIgnoreCase(m.getProperty("databaseSha256","")))throw new IOException("数据库 SHA-256 校验失败");return m;}}
    private static SecretKey derive(byte[]salt)throws Exception{PBEKeySpec s=new PBEKeySpec(KEY,salt,ITERATIONS,256);try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(s).getEncoded(),"AES");}finally{s.clearPassword();}}
    private static String sha256(Path p)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[]b=new byte[65536];for(int n;(n=in.read(b))>0;)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    private static void addFile(ZipOutputStream out,Path p,String name)throws IOException{out.putNextEntry(new ZipEntry(name));Files.copy(p,out);out.closeEntry();}
    private static void usage(){System.err.println("用法：\n  java tools/SafetyDataTool.java info <文件.safetydata>\n  java tools/SafetyDataTool.java verify <文件.safetydata>\n  java tools/SafetyDataTool.java extract <文件.safetydata> <输出目录>\n  java tools/SafetyDataTool.java pack <已解包目录> <输出文件.safetydata>");System.exit(2);}
}
