package com.bunshock.note_app_for_it_frontend.services;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Replaces WindowsDPAPIService as of 2026-07-08: DPAPI ties every encrypted value to the
 * specific Windows account that encrypted it, which made pre-configuring shared
 * organizational credentials (AD token, GLPI key, SMTP password, DB password) across many
 * technician machines impractical without visiting each one. This key is fixed and shared
 * across every installation by design — see CLAUDE.md's Security requirements section for
 * the accepted trade-off (anyone with the installed app can extract this key and decrypt
 * any copy of data/noteapp.db's encrypted settings; this is materially weaker than DPAPI
 * against a determined local attacker, but requires zero per-machine setup).
 *
 * If this key is ever rotated, every previously-encrypted APP_SETTINGS value becomes
 * undecryptable — all four secrets would need to be re-entered.
 */
public class AppKeyEncryptionService {

    // Regenerate via utils.AppKeyEncryptionGenerator if this ever needs rotating.
    private static final String KEY_BASE64 = "VY1rfD7Eph73eOORcn9opOWEdIWUcoOvKdOWSKEhAmc=";

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private static final AppKeyEncryptionService INSTANCE = new AppKeyEncryptionService();

    private final SecretKeySpec key;

    private AppKeyEncryptionService() {
        key = new SecretKeySpec(Base64.getDecoder().decode(KEY_BASE64), "AES");
    }

    public static AppKeyEncryptionService getInstance() {
        return INSTANCE;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
