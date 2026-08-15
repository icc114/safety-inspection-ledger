from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:100]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')


# 1) Android Keystore self-healing. Old encrypted credentials cannot be recovered after
# a device invalidates the key, so decrypt reports ResetRequiredException; new values can
# then be encrypted with a freshly generated key without touching business data.
Path('app/src/main/java/cn/safetyledger/app/security/SecretStore.java').write_text(r'''package cn.safetyledger.app.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyPermanentlyInvalidatedException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.UnrecoverableKeyException;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecretStore {
    private static final String ALIAS = "safety-ledger-local-secrets-v1";

    public static final class ResetRequiredException extends GeneralSecurityException {
        public ResetRequiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) return generateKey();
        try {
            KeyStore.Entry entry = store.getEntry(ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry secret) return secret.getSecretKey();
        } catch (Exception error) {
            if (!isInvalidated(error)) throw error;
            reset();
            return generateKey();
        }
        reset();
        return generateKey();
    }

    private SecretKey generateKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public void reset() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }

    public String encrypt(String value) throws Exception {
        if (value == null || value.isBlank()) return "";
        try {
            return encryptOnce(value);
        } catch (Exception error) {
            if (!isInvalidated(error)) throw error;
            reset();
            return encryptOnce(value);
        }
    }

    private String encryptOnce(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] all = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(encrypted, 0, all, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(all);
    }

    public String decrypt(String value) throws Exception {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] all = Base64.getDecoder().decode(value);
            if (all.length < 13) throw new GeneralSecurityException("本机加密配置格式损坏");
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(all, 12, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            if (!isInvalidated(error)) throw error;
            try { reset(); } catch (Exception ignored) {}
            throw new ResetRequiredException(
                    "本机安全密钥已失效，旧的云同步密码无法继续解密，请重新输入同步密码后保存。检查记录、照片和签名不会受影响。",
                    error);
        }
    }

    private static boolean isInvalidated(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof KeyPermanentlyInvalidatedException
                    || current instanceof UnrecoverableKeyException
                    || current instanceof AEADBadTagException) return true;
            if (current instanceof InvalidKeyException || current instanceof ProviderException) {
                String message = current.getMessage();
                if (message != null) {
                    String lower = message.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("permanently invalidated")
                            || lower.contains("key invalidated")
                            || lower.contains("keystore operation failed")) return true;
                }
            }
        }
        return false;
    }
}
''', encoding='utf-8')

# 2) Allow the UI to stop background jobs while credentials need re-entry.
Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncScheduler.java').write_text(r'''package cn.safetyledger.app.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class CloudSyncScheduler {
    private static final int JOB_ID = 1142026;
    private CloudSyncScheduler() {}

    public static void schedule(Context context) {
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }
}
''', encoding='utf-8')

# 3) Inspection-type filter must refresh after users add templates and must also retain
# historical inspection types even if their templates are later disabled/deleted.
replace('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java',
'''    public Template template(String id){for(Template t:templates(true))if(t.id.equals(id))return t;return null;}''',
'''    public Template template(String id){for(Template t:templates(true))if(t.id.equals(id))return t;return null;}\n    public List<String> inspectionTypes(){List<String>out=new ArrayList<>();try(Cursor c=raw().rawQuery("SELECT DISTINCT inspection_type FROM inspections WHERE deleted_at IS NULL AND inspection_type<>'' ORDER BY inspection_type",null)){while(c.moveToNext())out.add(c.getString(0));}return out;}''')

replace('app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        statusFilter = spinner(new String[]{"全部状态", "待整改", "整改中", "已整改完成", "检查完成"});''',
'''        statusFilter = spinner(new String[]{"全部状态", "草稿", "待整改", "整改中", "已整改完成", "检查完成"});''')

replace('app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        for (Template template : templates) values.add(template.category);\n        return values.toArray(new String[0]);\n    }''',
'''        for (Template template : templates) values.add(template.category);\n        values.addAll(repo.inspectionTypes());\n        return values.toArray(new String[0]);\n    }\n\n    private void refreshTypeFilter() {\n        if (type == null) return;\n        String previous = type.getSelectedItem() == null ? "全部检查类型"\n                : String.valueOf(type.getSelectedItem());\n        String[] values = types();\n        type.setAdapter(new ArrayAdapter<>(this,\n                android.R.layout.simple_spinner_dropdown_item, values));\n        int selectedIndex = 0;\n        for (int i = 0; i < values.length; i++) {\n            if (previous.equals(values[i])) { selectedIndex = i; break; }\n        }\n        type.setSelection(selectedIndex, false);\n    }''')

replace('app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        return new String[]{null, "PENDING_RECTIFICATION", "RECTIFYING", "RECTIFIED", "COMPLETED"}\n                [statusFilter.getSelectedItemPosition()];''',
'''        return new String[]{null, "DRAFT", "PENDING_RECTIFICATION", "RECTIFYING", "RECTIFIED", "COMPLETED"}\n                [statusFilter.getSelectedItemPosition()];''')

replace('app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        if (records != null) {\n            syncCalendar();\n            load();\n        }''',
'''        if (records != null) {\n            refreshTypeFilter();\n            syncCalendar();\n            load();\n        }''')

# 4) Downsample photos before placing them into PDFs. The form layout does not need
# full camera resolution; this substantially reduces output size while keeping print quality.
replace('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java',
'''            Bitmap bitmap = BitmapFactory.decodeFile(signature.path);''',
'''            Bitmap bitmap = decodeForPdf(signature.path, 700, 320, true);''')
replace('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java',
'''            Bitmap bitmap = BitmapFactory.decodeFile(media.localPath);''',
'''            Bitmap bitmap = decodeForPdf(media.localPath, 900, 1100, false);''')
replace('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java',
'''    private RectF fit(Bitmap bitmap, float x, float y, float width, float height) {''',
'''    private Bitmap decodeForPdf(String path, int maxWidth, int maxHeight, boolean preserveAlpha) {\n        BitmapFactory.Options bounds = new BitmapFactory.Options();\n        bounds.inJustDecodeBounds = true;\n        BitmapFactory.decodeFile(path, bounds);\n        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;\n        int sample = 1;\n        while (bounds.outWidth / sample > maxWidth * 2\n                || bounds.outHeight / sample > maxHeight * 2) sample *= 2;\n        BitmapFactory.Options options = new BitmapFactory.Options();\n        options.inSampleSize = Math.max(1, sample);\n        options.inPreferredConfig = preserveAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;\n        Bitmap decoded = BitmapFactory.decodeFile(path, options);\n        if (decoded == null) return null;\n        float scale = Math.min(1f, Math.min(maxWidth / (float) decoded.getWidth(),\n                maxHeight / (float) decoded.getHeight()));\n        if (scale >= .999f) return decoded;\n        Bitmap scaled = Bitmap.createScaledBitmap(decoded,\n                Math.max(1, Math.round(decoded.getWidth() * scale)),\n                Math.max(1, Math.round(decoded.getHeight() * scale)), true);\n        if (scaled != decoded) decoded.recycle();\n        return scaled;\n    }\n\n    private RectF fit(Bitmap bitmap, float x, float y, float width, float height) {''')

# 5) Cloud settings: retain the service page, recover invalidated Android Keystore state,
# and make the six-month archive behavior explicit and OFF by default.
replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''                "SELECT provider_type,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret FROM sync_providers WHERE enabled=1 ORDER BY updated_at DESC LIMIT 1",\n                null)) {\n            if (!cursor.moveToFirst()) {''',
'''                "SELECT provider_type,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret,enabled FROM sync_providers ORDER BY enabled DESC,updated_at DESC LIMIT 1",\n                null)) {\n            if (!cursor.moveToFirst()) {''')

replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''            String type = cursor.getString(0);\n            String displayType = "Cloudflare".equals(type) ? "Cloudflare" : "WebDAV / NAS";\n            setProviderSelection(displayType);\n            syncEnabledStatus.setText("云同步：已启用 · " + displayType);\n            syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));\n            if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");\n            endpoint.setText(cursor.getString(1));\n            user.setText(cursor.getString(2));\n            SecretStore store = new SecretStore();\n            savedServerPassword = store.decrypt(cursor.getString(3));\n            savedToken = store.decrypt(cursor.getString(4));\n            savedSpacePassword = store.decrypt(cursor.getString(6));''',
'''            String type = cursor.getString(0);\n            String displayType = "Cloudflare".equals(type) ? "Cloudflare" : "WebDAV / NAS";\n            boolean enabled = cursor.getInt(7) == 1;\n            setProviderSelection(displayType);\n            syncEnabledStatus.setText(enabled ? "云同步：已启用 · " + displayType\n                    : "云同步：未启用 · 已保留上次配置");\n            syncEnabledStatus.setTextColor(enabled ? Color.rgb(22, 128, 57) : Ui.MUTED);\n            if (syncSaveButton != null) syncSaveButton.setText(enabled ? "已启用 · 保存修改" : "保存并启用");\n            endpoint.setText(cursor.getString(1));\n            user.setText(cursor.getString(2));\n            SecretStore store = new SecretStore();\n            try {\n                savedServerPassword = store.decrypt(cursor.getString(3));\n                savedToken = store.decrypt(cursor.getString(4));\n                savedSpacePassword = store.decrypt(cursor.getString(6));\n            } catch (SecretStore.ResetRequiredException invalidated) {\n                repo.raw().execSQL("UPDATE sync_providers SET encrypted_secret='',token_ciphertext='',encryption_secret='',enabled=0 WHERE id='active-provider'");\n                CloudSyncScheduler.cancel(this);\n                savedServerPassword = "";\n                savedToken = "";\n                savedSpacePassword = "";\n                syncEnabledStatus.setText("云同步：未启用 · 本机安全密钥已重置");\n                syncEnabledStatus.setTextColor(Ui.DANGER);\n                syncStatus.setText("同步状态：请重新输入同步密码，然后点击保存并启用");\n                if (syncSaveButton != null) syncSaveButton.setText("保存并启用");\n                new AlertDialog.Builder(this)\n                        .setTitle("本机安全密钥已重置")\n                        .setMessage("手机系统使旧的本机加密密钥失效。APP 已自动清理无法解密的云端凭据；检查记录、照片、签名和模板均未删除。请重新输入同步密码后点击“保存并启用”。")\n                        .setPositiveButton("知道了", null).show();\n            }''')

replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''        Button password = Ui.secondaryButton(this, "未使用云同步：设置本机永久删除密码");\n        password.setOnClickListener(view -> setDeletePassword());\n        card.addView(password);\n        TextView archive = Ui.text(this,\n                "未整改完成的记录不会自动清理。超过六个月的自动归档与释放空间功能将在云端文件能够真实校验后启用，当前保持关闭以避免资料丢失。",\n                13, false);''',
'''        Button password = Ui.secondaryButton(this, "设置 / 修改永久删除密码");\n        password.setOnClickListener(view -> setDeletePassword());\n        card.addView(password);\n        TextView archiveState = Ui.text(this, "自动归档：关闭（不会自动删除本机记录）", 14, true);\n        archiveState.setTextColor(Ui.BLUE_DARK);\n        card.addView(archiveState);\n        TextView archive = Ui.text(this,\n                "“超过六个月自动归档”原本用于手机空间不足时，将已完成且已在 PC/云端完整校验的旧记录转为归档副本，再按用户选择释放手机上的原始照片。当前版本不执行自动归档、不自动删除任何记录或照片；待 PC 客户端和归档校验全部完成后再提供可选开关。",\n                13, false);''')

# Version bump.
replace('app/build.gradle', "versionCode 11\n        versionName '1.2.8'",
        "versionCode 12\n        versionName '1.2.9'")

print('v1.2.9 patch applied')
