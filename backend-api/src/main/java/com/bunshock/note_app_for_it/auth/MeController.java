package com.bunshock.note_app_for_it.auth;

import com.bunshock.note_app_for_it.auth.dto.GlpiTokenRequest;
import com.bunshock.note_app_for_it.auth.dto.MeResponse;
import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.common.security.EncryptionService;
import com.bunshock.note_app_for_it.rbac.AppUserRecord;
import com.bunshock.note_app_for_it.rbac.AppUserRepository;
import com.bunshock.note_app_for_it.rbac.RolePermissionRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** §7.1 — re-fetchable identity/permission snapshot, no fresh login required. */
@RestController
public class MeController {

    private final CurrentUser currentUser;
    private final AppUserRepository appUsers;
    private final RolePermissionRepository rolePermissions;
    private final EncryptionService encryption;

    public MeController(CurrentUser currentUser, AppUserRepository appUsers,
            RolePermissionRepository rolePermissions, EncryptionService encryption) {
        this.currentUser = currentUser;
        this.appUsers = appUsers;
        this.rolePermissions = rolePermissions;
        this.encryption = encryption;
    }

    @GetMapping("/api/v1/me")
    public MeResponse me() {
        CallerPrincipal caller = currentUser.require();
        Optional<AppUserRecord> record = appUsers.findByUsername(caller.username());
        List<String> permissions = rolePermissions.getPermissionsForRole(caller.role()).stream()
                .map(Enum::name).toList();
        return new MeResponse(
                caller.username(),
                caller.role(),
                caller.sedeId(),
                caller.fullName(), // AD full name from login (username fallback if the directory
                                    // was unreachable then). The greeting override, if any, is client-local.
                permissions,
                record.isPresent(),
                record.map(AppUserRecord::bypassGroupCheck).orElse(false));
    }

    /**
     * The technician sets their own GLPI {@code user_token} (F1). Write-only — stored
     * AES-encrypted in {@code APP_USER.glpi_token_encrypted}, never returned by {@code GET /me}.
     */
    @PutMapping("/api/v1/me/glpi-token")
    public void setGlpiToken(@Valid @RequestBody GlpiTokenRequest request) {
        CallerPrincipal caller = currentUser.require();
        appUsers.setGlpiTokenEncrypted(caller.username(), encryption.encrypt(request.token().trim()));
    }
}
