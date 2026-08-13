package cn.safetyledger.app.backup

import android.content.Context
import cn.safetyledger.app.data.AppDatabase
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class BackupService(private val context: Context) {
    private val magic = "CSINSPECT\u0001".toByteArray()
    private val legacyMagic = "SAFETYDATA\u0001".toByteArray()

    fun export(password: CharArray, out: OutputStream) {
        require(password.size >= 8) { "备份密码至少8位" }
        AppDatabase.get(context).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { while (it.moveToNext()) Unit }
        val plain = ByteArrayOutputStream()
        ZipOutputStream(plain).use { zip ->
            val meta = JSONObject().put("formatVersion", 1).put("schemaVersion", 1).put("createdAt", System.currentTimeMillis()).toString().toByteArray()
            zip.putNextEntry(ZipEntry("metadata.json")); zip.write(meta); zip.closeEntry()
            val database = context.getDatabasePath("safety-ledger-v1.db")
            if (database.exists()) addFile(zip, database, "database.db")
            val media = File(context.filesDir, "media")
            if (media.exists()) media.walkTopDown().filter { it.isFile }.forEach { addFile(zip, it, "media/${it.relativeTo(media).invariantSeparatorsPath}") }
        }
        val salt = random(16); val nonce = random(12)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 210_000, 256))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        out.write(magic); out.write(salt); out.write(nonce); out.write(cipher.doFinal(plain.toByteArray()))
    }

    fun restore(password: CharArray, input: InputStream) {
        val decrypted = decrypt(password, input)
        val temp = File(context.cacheDir, "restore-${System.currentTimeMillis()}").apply { mkdirs() }
        ZipInputStream(ByteArrayInputStream(decrypted)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(temp, entry.name)
                require(target.canonicalPath.startsWith(temp.canonicalPath + File.separator)) { "备份文件路径异常" }
                if (entry.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                zip.closeEntry()
            }
        }
        require(File(temp, "metadata.json").exists() && File(temp, "database.db").exists()) { "备份内容不完整" }
        AppDatabase.closeForRestore()
        val targetDb = context.getDatabasePath("safety-ledger-v1.db"); targetDb.parentFile?.mkdirs()
        File(temp, "database.db").copyTo(targetDb, overwrite = true)
        File(targetDb.path + "-wal").delete(); File(targetDb.path + "-shm").delete()
        val restoredMedia = File(temp, "media")
        val mediaRoot = File(context.filesDir, "media").apply { mkdirs() }
        if (restoredMedia.exists()) restoredMedia.walkTopDown().filter { it.isFile }.forEach { file -> val target = File(mediaRoot, file.relativeTo(restoredMedia).invariantSeparatorsPath); target.parentFile?.mkdirs(); file.copyTo(target, overwrite = true) }
    }

    private fun decrypt(password: CharArray, input: InputStream): ByteArray {
        val all = input.readBytes()
        val header = when {
            all.size > magic.size + 28 && all.copyOfRange(0, magic.size).contentEquals(magic) -> magic.size
            all.size > legacyMagic.size + 28 && all.copyOfRange(0, legacyMagic.size).contentEquals(legacyMagic) -> legacyMagic.size
            else -> error("不是有效的 .csinspect 备份文件")
        }
        val salt = all.copyOfRange(header, header + 16); val nonce = all.copyOfRange(header + 16, header + 28)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 210_000, 256))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(all.copyOfRange(header + 28, all.size))
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) { zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
    private fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
}
