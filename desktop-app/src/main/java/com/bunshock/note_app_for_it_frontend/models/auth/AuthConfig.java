package com.bunshock.note_app_for_it_frontend.models.auth;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /api/v1/auth/config} — what the client needs to start an OIDC login. Returned only
 * when the middleware has an IdP wired; a {@code 503 IDP_NOT_CONFIGURED} instead means the
 * middleware is running its dev profile and the client should fall back to dev-login.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthConfig(
        String issuer,
        String clientId,
        List<String> scopes,
        String authBackendDisplayName) {
}
