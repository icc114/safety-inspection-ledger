package cn.safetyledger.app.backup;

import android.content.*;import android.database.Cursor;import android.database.sqlite.SQLiteDatabase;import android.os.*;import cn.safetyledger.app.SafetyLedgerApp;import cn.safetyledger.app.data.LedgerDatabase;import java.io.*;import java.nio.ByteBuffer;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.security.*;import java.util.*;import java.util.zip.*;import javax.crypto.*;import javax.crypto.spec.*;

public final class BackupService{
    private static final byte[] MAGIC="SAFETYDATA".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PORTABLE_MAGIC="SAFETYLOCAL2".getBytes(StandardCharsets.US_ASCII);
    private static final char[] PORTABLE_KEY="safety-ledger-portable-backup-v2".toCharArray();
    private static final int FORMAT=2,PAYLOAD_FORMAT=1,ITERATIONS=310000,CHUNK_SIZE=256*1024,GCM_TAG_BYTES=16;
    private final Context context;public BackupService(Context c){context=c.getApplicationContext();}
    public void exportData(OutputStream destination,char[]password)throws Exception{
        if(password.length<8)throw new IllegalArgumentException("密码至少 8 位");
        exportInternal(destination,password,MAGIC,true);
    }
    /** Cloud transport only needs the business JPEGs; untouched originals stay on the source phone. */
    public void exportCloudSnapshot(OutputStream destination,char[]password)throws Exception{
        if(password.length<8)throw new IllegalArgumentException("密码至少 8 位");
        exportInternal(destination,password,MAGIC,false);
    }
    public void exportPortable(OutputStream destination)throws Exception{
        exportInternal(destination,PORTABLE_KEY.clone(),PORTABLE_MAGIC,true);
    }
    private void exportInternal(OutputStream destination,char[]password,byte[]magic,boolean includeOriginals)throws Exception{
        File tmp=File.createTempFile("safety-backup-",".zip",context.getCacheDir());
        try{
            LedgerDatabase h=((SafetyLedgerApp)context).db();
            SQLiteDatabase db=h.getWritableDatabase();
            db.rawQuery("PRAGMA wal_checkpoint(FULL)",null).close();
            File dbFile=context.getDatabasePath(LedgerDatabase.NAME);
            Properties manifest=new Properties();
            manifest.setProperty("format","safetydata");
            manifest.setProperty("formatVersion",String.valueOf(PAYLOAD_FORMAT));
            manifest.setProperty("container","safety-ledger-portable");
            manifest.setProperty("appPackage","cn.safetyledger.app");
            manifest.setProperty("portableCompatibility","android-windows");
            manifest.setProperty("schemaVersion",String.valueOf(LedgerDatabase.VERSION));
            manifest.setProperty("createdAt",String.valueOf(System.currentTimeMillis()));
            manifest.setProperty("databaseSha256",sha(dbFile));
            try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(tmp))){
                entry(z,"manifest.properties",properties(manifest));
                file(z,"database.sqlite",dbFile);
                File media=new File(context.getFilesDir(),"business_media");
                zipDir(z,media,"business_media/",includeOriginals);
            }
            encrypt(tmp,destination,password,magic);
        }finally{
            tmp.delete();
            Arrays.fill(password,'\0');
        }
    }
    public boolean isPortable(InputStream source)throws IOException{
        return Arrays.equals(readExactly(source,PORTABLE_MAGIC.length),PORTABLE_MAGIC);
    }
    public boolean isLegacyEncrypted(InputStream source)throws IOException{
        return Arrays.equals(readExactly(source,MAGIC.length),MAGIC);
    }
    public RestorePackage decryptAndValidatePortable(InputStream source)throws Exception{
        return decryptAndValidateInternal(source,PORTABLE_KEY.clone(),PORTABLE_MAGIC);
    }
    public RestorePackage decryptAndValidate(InputStream source,char[]password)throws Exception{
        return decryptAndValidateInternal(source,password,MAGIC);
    }
    private RestorePackage decryptAndValidateInternal(InputStream source,char[]password,byte[]magic)throws Exception{
        File zip=File.createTempFile("safety-restore-",".zip",context.getCacheDir());
        File dir=new File(context.getCacheDir(),"restore-"+UUID.randomUUID());
        if(!dir.mkdirs())throw new IOException("无法创建恢复目录");
        try{
            decrypt(source,zip,password,magic);
            unzip(zip,dir);
            File manifestFile=new File(dir,"manifest.properties"),dbFile=new File(dir,"database.sqlite");
            if(!manifestFile.isFile()||!dbFile.isFile())throw new IOException("备份内容不完整");
            Properties m=new Properties();
            try(InputStream in=new FileInputStream(manifestFile)){m.load(in);}
            if(!"safetydata".equals(m.getProperty("format")))throw new IOException("不是 APP 数据备份");
            if(Integer.parseInt(m.getProperty("formatVersion","0"))>PAYLOAD_FORMAT)throw new IOException("备份格式版本过新");
            if(Integer.parseInt(m.getProperty("schemaVersion","0"))>LedgerDatabase.VERSION)throw new IOException("数据库版本过新，请先升级 APP");
            if(!sha(dbFile).equals(m.getProperty("databaseSha256")))throw new IOException("数据库完整性校验失败");
            SQLiteDatabase test=SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
            try(Cursor c=test.rawQuery("SELECT max(version) FROM schema_migrations",null)){
                if(!c.moveToFirst()||c.getInt(0)<1)throw new IOException("数据库迁移元数据无效");
            }finally{test.close();}
            return new RestorePackage(dir,dbFile);
        }catch(Exception e){
            deleteTree(dir);
            throw e;
        }finally{
            zip.delete();
            Arrays.fill(password,'\0');
        }
    }
    public void fullRestore(RestorePackage p)throws Exception{SafetyLedgerApp app=(SafetyLedgerApp)context;app.db().close();File target=context.getDatabasePath(LedgerDatabase.NAME);File recovery=new File(target.getParentFile(),LedgerDatabase.NAME+".before-restore");if(target.exists())Files.copy(target.toPath(),recovery.toPath(),StandardCopyOption.REPLACE_EXISTING);Files.copy(p.database.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);new File(target+"-wal").delete();new File(target+"-shm").delete();File mediaRoot=new File(context.getFilesDir(),"business_media");deleteTree(mediaRoot);copyMedia(new File(p.root,"business_media"),mediaRoot);try(SQLiteDatabase restored=SQLiteDatabase.openDatabase(target.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){normalizeMediaPaths(restored);scrubDeviceSpecificState(restored);}p.close();}
    public int mergeRestore(RestorePackage p)throws Exception{
        LedgerDatabase h=((SafetyLedgerApp)context).db();
        SQLiteDatabase d=h.getWritableDatabase();
        String path=p.database.getAbsolutePath().replace("'","''");
        int changed=0;
        String[] tables={"templates","template_items","inspections","inspection_items","media",
                "signatures","tombstones","archive_index","holiday_cache"};
        d.execSQL("ATTACH DATABASE '"+path+"' AS incoming");
        d.beginTransaction();
        try{
            for(String table:tables){
                if(!tableExists(d,"incoming",table))continue; // Accept older schema backups.
                Set<String>mainCols=columns(d,"main",table);
                Set<String>incomingCols=columns(d,"incoming",table);
                mainCols.retainAll(incomingCols);
                if(mainCols.isEmpty())continue;
                String names=String.join(",",mainCols);
                d.execSQL("INSERT OR IGNORE INTO main."+table+"("+names+") SELECT "+names
                        +" FROM incoming."+table);
                try(Cursor c=d.rawQuery("SELECT changes()",null)){if(c.moveToFirst())changed+=c.getInt(0);}

                // Provider credentials and per-device app settings are always kept local.
                if("sync_providers".equals(table)||"app_settings".equals(table))continue;
                String primary=mainCols.contains("id")?"id":mainCols.contains("device_id")?"device_id":null;
                if(primary==null||!mainCols.contains("updated_at"))continue;
                if(mainCols.contains("revision")){
                    d.execSQL("INSERT OR IGNORE INTO conflict_copies(id,entity_type,entity_id,local_revision,remote_revision,payload_json,created_at) "
                                    +"SELECT lower(hex(randomblob(16))),?,m."+primary+",COALESCE(m.revision,1),COALESCE(i.revision,1),'{}',? "
                                    +"FROM main."+table+" m JOIN incoming."+table+" i ON m."+primary+"=i."+primary
                                    +" WHERE COALESCE(m.updated_at,0)<>COALESCE(i.updated_at,0)",
                            new Object[]{table,System.currentTimeMillis()});
                }
                List<String>assignments=new ArrayList<>();
                for(String column:mainCols){
                    if(column.equals(primary))continue;
                    assignments.add(column+"=(SELECT i."+column+" FROM incoming."+table
                            +" i WHERE i."+primary+"=main."+table+"."+primary+")");
                }
                if(!assignments.isEmpty()){
                    d.execSQL("UPDATE main."+table+" SET "+String.join(",",assignments)
                            +" WHERE EXISTS(SELECT 1 FROM incoming."+table+" i WHERE i."+primary
                            +"=main."+table+"."+primary+" AND COALESCE(i.updated_at,0)>COALESCE(main."
                            +table+".updated_at,0))");
                    try(Cursor c=d.rawQuery("SELECT changes()",null)){if(c.moveToFirst())changed+=c.getInt(0);}
                }
            }
            d.setTransactionSuccessful();
        }finally{
            d.endTransaction();
            d.execSQL("DETACH DATABASE incoming");
        }
        copyMedia(new File(p.root,"business_media"),new File(context.getFilesDir(),"business_media"));
        normalizeMediaPaths(d);
        p.close();
        return changed;
    }
    public void restoreInspection(RestorePackage p,String inspectionId)throws Exception{
        LedgerDatabase h=((SafetyLedgerApp)context).db();SQLiteDatabase d=h.getWritableDatabase();
        String path=p.database.getAbsolutePath().replace("'","''");
        d.execSQL("ATTACH DATABASE '"+path+"' AS incoming");d.beginTransaction();
        try{
            try(Cursor c=d.rawQuery("SELECT 1 FROM incoming.inspections WHERE id=? LIMIT 1",new String[]{inspectionId})){
                if(!c.moveToFirst())throw new IOException("恢复包中找不到该检查记录");
            }
            d.delete("inspections","id=?",new String[]{inspectionId});
            d.execSQL("INSERT INTO main.inspections SELECT * FROM incoming.inspections WHERE id=?",new Object[]{inspectionId});
            for(String table:new String[]{"inspection_items","media","signatures"}){
                if(tableExists(d,"incoming",table))d.execSQL("INSERT OR REPLACE INTO main."+table+" SELECT * FROM incoming."+table+" WHERE inspection_id=?",new Object[]{inspectionId});
            }
            d.setTransactionSuccessful();
        }finally{d.endTransaction();d.execSQL("DETACH DATABASE incoming");}
        File source=new File(new File(p.root,"business_media"),inspectionId);
        File target=new File(new File(context.getFilesDir(),"business_media"),inspectionId);
        deleteTree(target);copyMedia(source,target);normalizeMediaPaths(d);p.close();
    }

    private void normalizeMediaPaths(SQLiteDatabase d){File root=new File(context.getFilesDir(),"business_media");try(Cursor c=d.rawQuery("SELECT id,inspection_id FROM media WHERE deleted_at IS NULL",null)){while(c.moveToNext()){File f=new File(new File(root,c.getString(1)),c.getString(0)+".jpg");if(f.isFile())d.execSQL("UPDATE media SET local_path=? WHERE id=?",new Object[]{f.getAbsolutePath(),c.getString(0)});}}try(Cursor c=d.rawQuery("SELECT id,inspection_id,role,local_path FROM signatures WHERE deleted_at IS NULL",null)){while(c.moveToNext()){String stored=c.getString(3);String name=stored==null||stored.isBlank()?"signature-"+c.getString(2)+".png":new File(stored).getName();File f=new File(new File(root,c.getString(1)),name);if(f.isFile())d.execSQL("UPDATE signatures SET local_path=? WHERE id=?",new Object[]{f.getAbsolutePath(),c.getString(0)});}}}
    private boolean tableExists(SQLiteDatabase d,String schema,String table){try(Cursor c=d.rawQuery("SELECT 1 FROM "+schema+".sqlite_master WHERE type='table' AND name=?",new String[]{table})){return c.moveToFirst();}}
    private Set<String> columns(SQLiteDatabase d,String schema,String t){Set<String>s=new LinkedHashSet<>();try(Cursor c=d.rawQuery("PRAGMA "+schema+".table_info("+t+")",null)){while(c.moveToNext())s.add(c.getString(1));}return s;}
    private void scrubDeviceSpecificState(SQLiteDatabase d){
        if(tableExists(d,"main","sync_providers")){
            d.execSQL("UPDATE sync_providers SET enabled=0,encrypted_secret='',token_ciphertext='',encryption_secret=''");
        }
        if(tableExists(d,"main","app_settings")){
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
        if(tableExists(d,"main","sync_devices")) d.delete("sync_devices",null,null);
    }
    private void encrypt(File plain,OutputStream out,char[]pw,byte[]magic)throws Exception{
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
    private SecretKey derive(char[]pw,byte[]salt)throws Exception{PBEKeySpec s=new PBEKeySpec(pw,salt,ITERATIONS,256);byte[]k=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(s).getEncoded();s.clearPassword();return new SecretKeySpec(k,"AES");}
    private byte[]random(int n){byte[]b=new byte[n];new SecureRandom().nextBytes(b);return b;}private byte[]properties(Properties p)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();p.store(b,"Safety Ledger portable backup");return b.toByteArray();}
    private void entry(ZipOutputStream z,String name,byte[]b)throws IOException{z.putNextEntry(new ZipEntry(name));z.write(b);z.closeEntry();}private void file(ZipOutputStream z,String name,File f)throws IOException{z.putNextEntry(new ZipEntry(name));Files.copy(f.toPath(),z);z.closeEntry();}private void zipDir(ZipOutputStream z,File dir,String prefix,boolean includeOriginals)throws IOException{if(!dir.isDirectory())return;File[]fs=dir.listFiles();if(fs==null)return;for(File f:fs)if(f.isDirectory())zipDir(z,f,prefix+f.getName()+"/",includeOriginals);else if(includeOriginals||!f.getName().endsWith("-original.bin"))file(z,prefix+f.getName(),f);}
    private void unzip(File zip,File dir)throws IOException{try(ZipInputStream z=new ZipInputStream(new FileInputStream(zip))){for(ZipEntry e;(e=z.getNextEntry())!=null;){File out=new File(dir,e.getName()).getCanonicalFile();if(!out.toPath().startsWith(dir.getCanonicalFile().toPath()))throw new IOException("备份路径非法");if(e.isDirectory())out.mkdirs();else{out.getParentFile().mkdirs();try(OutputStream o=new FileOutputStream(out)){copy(z,o);}}}}}
    private static byte[] readExactly(InputStream in,int length)throws IOException{byte[]out=new byte[length];int offset=0;while(offset<length){int n=in.read(out,offset,length-offset);if(n<0)throw new EOFException("备份文件被截断");offset+=n;}return out;}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[]b=new byte[65536];for(int n;(n=in.read(b))>=0;)if(n>0)out.write(b,0,n);}
    private void copyMedia(File from,File to)throws IOException{if(!from.exists())return;Files.walk(from.toPath()).forEach(p->{try{Path rel=from.toPath().relativize(p),dest=to.toPath().resolve(rel);if(Files.isDirectory(p))Files.createDirectories(dest);else if(!Files.exists(dest))Files.copy(p,dest);}catch(IOException e){throw new UncheckedIOException(e);}});}
    private String sha(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream i=new FileInputStream(f)){byte[]b=new byte[65536];for(int n;(n=i.read(b))>0;)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format("%02x",x));return s.toString();}
    private static void deleteTree(File f){if(f.isDirectory()){File[]a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}f.delete();}
    public static final class RestorePackage implements AutoCloseable{public final File root,database;RestorePackage(File r,File d){root=r;database=d;}public void close(){deleteTree(root);}}
}
