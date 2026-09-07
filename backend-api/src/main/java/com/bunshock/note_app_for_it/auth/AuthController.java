package com.bunshock.note_app_for_it.auth;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.auth.dto.AuthConfigResponse;
import com.bunshock.note_app_for_it.auth.dto.LoginResponse;
import com.bunshock.note_app_for_it.common.security.SessionStore;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.rbac.AppUserRecord;
import com.bunshock.note_app_for_it.rbac.AppUserRepository;
import com.bunshock.note_app_for_it.rbac.RolePermissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/** backend-contract.md §2 — see auth-flow.md for the full client-side sequence this backs. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final IdpProperties idpProperties;
    private final IdpTokenValidator tokenValidator;
    private final AppUserRepository appUsers;
    private final RolePermissionRepository rolePermissions;
    private final SessionStore sessionStore;
    private final AuditRepository auditRepository;

    public AuthController(IdpProperties idpProperties, IdpTokenValidator tokenValidator,
            AppUserRepository appUsers, RolePermissionRepository rolePermissions,
            SessionStore sessionStore, AuditRepository auditRepository) {
        this.idpProperties = idpProperties;
        this.tokenValidator = tokenValidator;
        this.appUsers = appUsers;
        this.rolePermissions = rolePermissions;
        this.sessionStore = sessionStore;
        this.auditRepository = auditRepository;
    }

    @GetMapping("/config")
    public AuthConfigResponse config() {
        if (!idpProperties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDP_NOT_CONFIGURED",
                    "El proveedor de identidad no está configurado todavía.");
        }
        return new AuthConfigResponse(
                idpProperties.getIssuerUri(),
                idpProperties.getClientId(),
                List.of("openid", "profile"),
                "Keycloak");
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestHeader("Authorization") String authorizationHeader) {
        String idpToken = bearerToken(authorizationHeader);
        Jwt jwt = tokenValidator.validate(idpToken);
        String username = jwt.getClaimAsString(idpProperties.getUsernameClaim());
        if (username == null || username.isBlank()) {
            throw ApiException.unauthorized("INVALID_IDP_TOKEN", "El token no contiene un usuario válido.");
        }

        Optional<AppUserRecord> appUser = appUsers.findByUsername(username);
        if (appUser.isEmpty()) {
            auditRepository.recordLogin(username, false, "NOT_REGISTERED");
            throw ApiException.forbidden("USER_NOT_REGISTERED",
                    "Usuario no registrado en la aplicación. Solicite acceso a un administrador.");
        }
        AppUserRecord user = appUser.get();

        List<String> groups = jwt.getClaimAsStringList(idpProperties.getGroupsClaim());
        boolean groupCheckRequired = idpProperties.getAllowedGroupName() != null
                && !idpProperties.getAllowedGroupName().isBlank();
        boolean inAllowedGroup = groups != null && groups.contains(idpProperties.getAllowedGroupName());
        if (groupCheckRequired && !inAllowedGroup && !user.bypassGroupCheck()) {
            auditRepository.recordLogin(username, false, "NOT_IN_ALLOWED_GROUP");
            throw ApiException.forbidden("NOT_IN_ALLOWED_GROUP",
                    "No tiene permisos para usar esta aplicación.");
        }

        String token = sessionStore.create(user.username(), user.role(), user.sedeId());
        auditRepository.recordLogin(username, true, null);

        List<String> permissions = rolePermissions.getPermissionsForRole(user.role()).stream()
                .map(Enum::name).toList();
        // displayName is just the username for now — see MeController's matching TODO for the
        // real "Nombre para mostrar" preference, not yet ported (Phase B).
        return new LoginResponse(token, sessionStore.expiresAt(token), user.role(), user.sedeId(),
                user.username(), permissions);
    }

    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String authorizationHeader) {
        sessionStore.invalidate(bearerToken(authorizationHeader));
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw ApiException.unauthorized("MISSING_TOKEN", "Falta el token de autorización.");
        }
        return authorizationHeader.substring("Bearer ".length()).trim();
    }
}
