package com.bunshock.note_app_for_it.adapters.glpi;

import com.bunshock.note_app_for_it.common.security.EncryptionService;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.rbac.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Resolves the acting technician's GLPI {@code user_token} (F1). Each technician sets their own
 * via {@code PUT /api/v1/me/glpi-token}; it's stored AES-encrypted in
 * {@code APP_USER.glpi_token_encrypted}. A technician who hasn't set one cannot perform
 * {@code SYNC_EXTERNAL} / {@code VALIDATE_RETURNS} writes.
 */
@Component
public class GlpiUserTokenResolver {

    private final AppUserRepository appUsers;
    private final EncryptionService encryption;

    public GlpiUserTokenResolver(AppUserRepository appUsers, EncryptionService encryption) {
        this.appUsers = appUsers;
        this.encryption = encryption;
    }

    public String resolveFor(String username) {
        String encrypted = appUsers.findGlpiTokenEncrypted(username)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "GLPI_TOKEN_NOT_SET",
                        "Configure su token de API de GLPI antes de sincronizar."));
        return encryption.decrypt(encrypted);
    }
}
