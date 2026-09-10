package com.bunshock.note_app_for_it_frontend.models.auth;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The middleware's login response ({@code POST /auth/login} and {@code /auth/dev-login}) — the
 * whole session in one payload, so the client populates {@code TechnicianSessionService} /
 * {@code AdminSession} without a follow-up {@code /me}. {@code expiresAt} is kept as the raw ISO
 * string (no {@code java.time} Jackson module on the classpath, and the client doesn't act on it).
 * {@code sedeId}/{@code sedeName}/{@code dni} may be null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionInfo(
        String sessionToken,
        String expiresAt,
        String username,
        String role,
        Integer sedeId,
        String sedeName,
        String fullName,
        String dni,
        List<String> permissions) {
}
