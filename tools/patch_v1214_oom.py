from pathlib import Path


def replace_required(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:220]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')


# Version bump.
replace_required('app/build.gradle', "versionCode 16\n        versionName '1.2.13'", "versionCode 17\n        versionName '1.2.14'")

# Backup encryption: Android/Conscrypt buffers a whole AES-GCM message until doFinal().
# With photo-heavy .safetydata files this can request tens of MB in one allocation and OOM.
# New v2 framing encrypts 256 KiB independently authenticated chunks while retaining v1 import.
p = Path('app/src/main/java/cn/safetyledger/app/backup/BackupService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('import java.io.*;import java.nio.charset.StandardCharsets;',
                    'import java.io.*;import java.nio.ByteBuffer;import java.nio.charset.StandardCharsets;', 1)
text = text.replace('private static final int FORMAT=1,ITERATIONS=310000;',
                    'private static final int FORMAT=2,PAYLOAD_FORMAT=1,ITERATIONS=310000,CHUNK_SIZE=256*1024,GCM_TAG_BYTES=16;', 1)
text = text.replace('manifest.setProperty("formatVersion","1");',
                    'manifest.setProperty("formatVersion",String.valueOf(PAYLOAD_FORMAT));', 1)
text = text.replace('if(Integer.parseInt(m.getProperty("formatVersion","0"))>FORMAT)throw new IOException("备份格式版本过新");',
                    'if(Integer.parseInt(m.getProperty("formatVersion","0"))>PAYLOAD_FORMAT)throw new IOException("备份格式版本过新");', 1)
old = '''    private void encrypt(File plain,OutputStream out,char[]pw,byte[]magic)throws Exception{byte[]salt=random(16),iv=random(12);SecretKey key=derive(pw,salt);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));out.write(magic);out.write(FORMAT);out.write(salt);out.write(iv);try(CipherOutputStream co=new CipherOutputStream(out,c)){Files.copy(plain.toPath(),co);}}
    private void decrypt(InputStream in,File out,char[]pw,byte[]expectedMagic)throws Exception{byte[]magic=readExactly(in,expectedMagic.length);if(!Arrays.equals(magic,expectedMagic))throw new IOException("文件不是 .safetydata APP 数据备份（PDF 不可导入）");int version=in.read();if(version<1||version>FORMAT)throw new IOException("不支持的备份格式版本");byte[]salt=readExactly(in,16),iv=readExactly(in,12);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,derive(pw,salt),new GCMParameterSpec(128,iv));try(CipherInputStream ci=new CipherInputStream(in,c);OutputStream o=new FileOutputStream(out)){copy(ci,o);}catch(IOException e){throw new SecurityException("密码错误或备份完整性校验失败",e);}}
'''
new = '''    private void encrypt(File plain,OutputStream out,char[]pw,byte[]magic)throws Exception{
        byte[]salt=random(16),baseNonce=random(12);SecretKey key=derive(pw,salt);
        out.write(magic);out.write(FORMAT);out.write(salt);out.write(baseNonce);
        DataOutputStream data=new DataOutputStream(out);
        byte[]buffer=new byte[CHUNK_SIZE];long counter=0;
        try(InputStream input=new BufferedInputStream(new FileInputStream(plain),65536)){
            for(int n;(n=input.read(buffer))>=0;){
                if(n==0)continue;
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,chunkNonce(baseNonce,counter)));
                cipher.updateAAD(chunkAad(magic,counter,n));
                byte[]encrypted=cipher.doFinal(buffer,0,n);
                data.writeInt(n);data.write(encrypted);counter++;
            }
        }
        data.writeInt(0);data.flush();
    }
    private void decrypt(InputStream in,File out,char[]pw,byte[]expectedMagic)throws Exception{
        byte[]magic=readExactly(in,expectedMagic.length);if(!Arrays.equals(magic,expectedMagic))throw new IOException("文件不是 .safetydata APP 数据备份（PDF 不可导入）");
        int version=in.read();if(version<1||version>FORMAT)throw new IOException("不支持的备份格式版本");
        byte[]salt=readExactly(in,16),iv=readExactly(in,12);SecretKey key=derive(pw,salt);
        if(version==1){decryptLegacy(in,out,key,iv);return;}
        decryptChunked(in,out,key,iv,expectedMagic);
    }
    private void decryptLegacy(InputStream in,File out,SecretKey key,byte[]iv)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));
        try(CipherInputStream ci=new CipherInputStream(in,cipher);OutputStream o=new BufferedOutputStream(new FileOutputStream(out),65536)){copy(ci,o);}catch(IOException e){throw new SecurityException("密码错误、旧备份过大或备份完整性校验失败",e);}
    }
    private void decryptChunked(InputStream in,File out,SecretKey key,byte[]baseNonce,byte[]magic)throws Exception{
        DataInputStream data=new DataInputStream(in);long counter=0;
        try(OutputStream output=new BufferedOutputStream(new FileOutputStream(out),65536)){
            while(true){
                int plainLength;
                try{plainLength=data.readInt();}catch(EOFException e){throw new EOFException("备份文件被截断");}
                if(plainLength==0)break;
                if(plainLength<0||plainLength>CHUNK_SIZE)throw new IOException("备份分块长度非法");
                byte[]encrypted=readExactly(data,plainLength+GCM_TAG_BYTES);
                try{
                    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,chunkNonce(baseNonce,counter)));
                    cipher.updateAAD(chunkAad(magic,counter,plainLength));
                    byte[]plain=cipher.doFinal(encrypted);
                    if(plain.length!=plainLength)throw new SecurityException("备份分块长度校验失败");
                    output.write(plain);counter++;
                }catch(GeneralSecurityException e){throw new SecurityException("密码错误或备份完整性校验失败",e);}
            }
        }
    }
    private byte[]chunkNonce(byte[]base,long counter){
        byte[]nonce=base.clone();long seed=ByteBuffer.wrap(base,4,8).getLong();ByteBuffer.wrap(nonce,4,8).putLong(seed+counter);return nonce;
    }
    private byte[]chunkAad(byte[]magic,long counter,int plainLength)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(magic.length+12);DataOutputStream data=new DataOutputStream(bytes);data.write(magic);data.writeLong(counter);data.writeInt(plainLength);data.flush();return bytes.toByteArray();
    }
'''
if old not in text:
    raise SystemExit('BackupService encryption block not found')
text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')

# Peer snapshots from 1.2.13 can be legacy single-message GCM. If one is too large for a vendor
# crypto provider, skip it instead of taking down the process. Once that peer updates it will
# replace the old file with v2 chunked encryption.
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('''                } catch (Exception peerError) {
                    skipped++;
                    String detail = readable(peerError);
''', '''                } catch (Throwable peerError) {
                    skipped++;
                    String detail = peerError instanceof OutOfMemoryError
                            ? "旧版云端快照过大，已跳过；请将该设备升级到 1.2.14 后重新同步"
                            : readable(peerError);
''', 1)
p.write_text(text, encoding='utf-8')

# Background jobs must never crash the whole app if the system is already under memory pressure.
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncJobService.java')
text = p.read_text(encoding='utf-8')
old = '''            try {
                new CloudSyncService(this).syncNow();
            } catch (Exception error) {
'''
new = '''            try {
                new CloudSyncService(this).syncNow();
            } catch (OutOfMemoryError error) {
                retry = false;
                String message = "同步数据较大且当前系统内存不足，本次后台同步已安全停止。请升级所有设备到 1.2.14 后重试。";
                repo.putSetting("last_sync_error", message);
                notifyFailure(message);
            } catch (Exception error) {
'''
if old not in text:
    raise SystemExit('CloudSyncJobService try/catch pattern not found')
text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')
