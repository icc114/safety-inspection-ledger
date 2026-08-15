package cn.safetyledger.app.security;

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
