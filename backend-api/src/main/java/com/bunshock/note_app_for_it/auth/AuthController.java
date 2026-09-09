package com.bunshock.note_app_for_it.auth;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.auth.dto.AuthConfigResponse;
import com.bunshock.note_app_for_it.auth.dto.LoginResponse;
import com.bunshock.note_app_for_it.common.security.SessionStore;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.directory.DirectoryService;
import com.bunshock.note_app_for_it.directory.dto.DirectoryUser;
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
    private final DirectoryService directoryService;

    public AuthController(IdpProperties idpProperties, IdpTokenValidator tokenValidator,
            AppUserRepository appUsers, RolePermissionRepository rolePermissions,
            SessionStore sessionStore, AuditRepository auditRepository,
            DirectoryService directoryService) {
        this.idpProperties = idpProperties;
        this.tokenValidator = tokenValidator;
        this.appUsers = appUsers;
        this.rolePermissions = rolePermissions;
        this.sessionStore = sessionStore;
        this.auditRepository = auditRepository;
        this.directoryService = directoryService;
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

        // Active Directory identity snapshot, taken once here (§7.6 — mirrors the desktop app's
        // TechnicianSessionService resolving identity at session start, not per action). Stamped
        // into the session so note creation reads a real name/DNI without a per-note lookup, and
        // returned to the client as the default greeting name. A cosmetic greeting override
        // ("call me X") is a desktop-client-local preference — it never reaches the middleware,
        // and technician_name on a note is always this AD value.
        // Best-effort: a directory outage must never block login — degrade to username + no DNI.
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
            // DIRECTORY_NOT_CONFIGURED (503) / DIRECTORY_UNAVAILABLE (502) — login still succeeds.
        }

        String token = sessionStore.create(user.username(), user.role(), user.sedeId(), fullName, dni);
        auditRepository.recordLogin(username, true, null);

        List<String> permissions = rolePermissions.getPermissionsForRole(user.role()).stream()
                .map(Enum::name).toList();
        return new LoginResponse(token, sessionStore.expiresAt(token), user.role(), user.sedeId(),
                fullName, permissions);
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
