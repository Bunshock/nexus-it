package com.bunshock.note_app_for_it.common.security;

import com.bunshock.note_app_for_it.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256/GCM, same algorithm/format as the desktop app's own
 * {@code AppKeyEncryptionService} — but a DIFFERENT key, deliberately: that class's key is a
 * fixed, unrotatable constant baked into every installed client binary, an accepted trade-off
 * only because the desktop app has no other way to distribute a shared key to end-user machines.
 * The middleware is a server — it takes a real, per-deployment key via
 * {@code middleware.security.encryption-key} / the {@code MIDDLEWARE_SECURITY_ENCRYPTION_KEY} env
 * var, instead of reusing (or shipping any) hardcoded constant. Deliberately NO default key is
 * embedded here or in application.yml — generate one with
 * {@code openssl rand -base64 32} (or equivalent) per deployment and set it out-of-band, the same
 * "genuinely open, not designed, don't guess a value" convention this project already uses for
 * {@code middleware.idp.issuer-uri}. Blank = every encrypt/decrypt call fails loudly
 * ({@code 503 ENCRYPTION_NOT_CONFIGURED}) rather than silently running with no real protection.
 * Rotating the key makes every previously-encrypted value undecryptable, same caveat the desktop
 * app's own class documents.
 */
@Component
public class EncryptionService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKeySpec key;

    public EncryptionService(@Value("${middleware.security.encryption-key:}") String keyBase64) {
        this.key = keyBase64.isBlank() ? null : new SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES");
    }

    public String encrypt(String plaintext) {
        requireConfigured();
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
        requireConfigured();
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

    private void requireConfigured() {
        if (key == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ENCRYPTION_NOT_CONFIGURED",
                    "El cifrado de secretos no está configurado en este despliegue todavía.");
        }
    }
}
