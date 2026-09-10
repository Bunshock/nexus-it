package com.bunshock.note_app_for_it.auth;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.auth.dto.DevLoginRequest;
import com.bunshock.note_app_for_it.auth.dto.LoginResponse;
import com.bunshock.note_app_for_it.common.security.SessionStore;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.directory.DirectoryService;
import com.bunshock.note_app_for_it.directory.dto.DirectoryUser;
import com.bunshock.note_app_for_it.rbac.AppUserRecord;
import com.bunshock.note_app_for_it.rbac.AppUserRepository;
import com.bunshock.note_app_for_it.rbac.RolePermissionRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DEV-ONLY. Mints a real middleware session for a seeded {@code APP_USER} with no Keycloak in the
 * loop — so the Phase B desktop client (and anyone running {@code mvn spring-boot:run}) can develop
 * against a working middleware without a container.
 *
 * <p>{@code @Profile("dev")} — this bean, and therefore the {@code /auth/dev-login} route, is
 * physically absent from a {@code sqlserver} (prod) build. The response is byte-identical to the
 * real {@code POST /auth/login} so a client can't tell which minted its session.
 */
@RestController
@Profile("dev")
@RequestMapping("/api/v1/auth")
public class DevAuthController {

    private static final Logger log = LoggerFactory.getLogger(DevAuthController.class);

    private final AppUserRepository appUsers;
    private final RolePermissionRepository rolePermissions;
    private final SessionStore sessionStore;
    private final AuditRepository auditRepository;
    private final DirectoryService directoryService;

    public DevAuthController(AppUserRepository appUsers, RolePermissionRepository rolePermissions,
            SessionStore sessionStore, AuditRepository auditRepository, DirectoryService directoryService) {
        this.appUsers = appUsers;
        this.rolePermissions = rolePermissions;
        this.sessionStore = sessionStore;
        this.auditRepository = auditRepository;
        this.directoryService = directoryService;
        log.warn("DEV AUTH ENABLED — POST /api/v1/auth/dev-login mints middleware sessions with NO Keycloak. "
                + "'dev' profile only; absent from any non-dev build.");
    }

    @PostMapping("/dev-login")
    public LoginResponse devLogin(@Valid @RequestBody DevLoginRequest request) {
        String username = request.username().trim();

        AppUserRecord user = appUsers.findByUsername(username).orElseThrow(() ->
                ApiException.forbidden("USER_NOT_REGISTERED",
                        "Usuario no registrado en la aplicación. Solicite acceso a un administrador."));

        // Same best-effort AD identity snapshot as the real login (AuthController). In dev the
        // directory is normally unconfigured, so this degrades to username + null DNI.
        String fullName = user.username();
        String dni = null;
        try {
            DirectoryUser profile = directoryService.findExactByUsername(username).orElse(null);
            if (profile != null) {
                if (profile.fullName() != null && !profile.fullName().isBlank()) {
                    fullName = profile.fullName();
                }
                dni = profile.dni();
            }
        } catch (ApiException directoryUnavailable) {
            // DIRECTORY_NOT_CONFIGURED (503) / DIRECTORY_UNAVAILABLE (502) — dev-login still succeeds.
        }

        String token = sessionStore.create(user.username(), user.role(), user.sedeId(), fullName, dni);
        auditRepository.recordLogin(username, true, null);

        List<String> permissions = rolePermissions.getPermissionsForRole(user.role()).stream()
                .map(Enum::name).toList();
        return new LoginResponse(token, sessionStore.expiresAt(token), user.role(), user.sedeId(),
                fullName, permissions);
    }
}
