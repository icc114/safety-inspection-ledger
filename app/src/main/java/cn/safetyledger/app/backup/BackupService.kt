package cn.safetyledger.app.backup

import android.content.Context
import org.json.JSONObject
import java.io.*
import java.security.*
import java.util.zip.*
import javax.crypto.*
import javax.crypto.spec.*

class BackupService(private val context:Context) {
 private val magic="SAFETYDATA\u0001".toByteArray()
 fun export(password:CharArray,out:OutputStream){ val plain=ByteArrayOutputStream(); ZipOutputStream(plain).use{z->
   val meta=JSONObject().put("formatVersion",1).put("schemaVersion",1).put("createdAt",System.currentTimeMillis()).toString().toByteArray()
   z.putNextEntry(ZipEntry("metadata.json"));z.write(meta);z.closeEntry()
   listOf(context.getDatabasePath("safety-ledger-v1.db") to "database.db",File(context.filesDir,"media") to "media").forEach{(f,n)->if(f.exists()){if(f.isDirectory)f.walkTopDown().filter{it.isFile}.forEach{x->z.putNextEntry(ZipEntry("$n/${x.relativeTo(f).invariantSeparatorsPath}"));x.inputStream().use{it.copyTo(z)};z.closeEntry()}else{z.putNextEntry(ZipEntry(n));f.inputStream().use{it.copyTo(z)};z.closeEntry()}}}
 }; val salt=random(16);val nonce=random(12);val key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password,salt,210000,256));val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,nonce));val encrypted=cipher.doFinal(plain.toByteArray());out.write(magic);out.write(salt);out.write(nonce);out.write(encrypted) }
 fun validate(password:CharArray,input:InputStream):ByteArray { val all=input.readBytes();require(all.size>magic.size+28&&all.copyOfRange(0,magic.size).contentEquals(magic)){"不是有效的 .safetydata 文件"};val salt=all.copyOfRange(magic.size,magic.size+16);val nonce=all.copyOfRange(magic.size+16,magic.size+28);val key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password,salt,210000,256));val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,nonce));return c.doFinal(all.copyOfRange(magic.size+28,all.size)) }
 private fun random(n:Int)=ByteArray(n).also{SecureRandom().nextBytes(it)}
}
