package com.bunshock.note_app_for_it.auth.dto;

import java.time.Instant;
import java.util.List;

/**
 * §2.1 — everything the session needs in one shot. {@code username}/{@code dni}/{@code sedeName}
 * are here (not only on {@code GET /me}) so the desktop client can populate its whole session from
 * the login response alone — no follow-up call, and the dev-login path has no id_token to read a
 * {@code preferred_username} claim from.
 */
public record LoginResponse(
        String sessionToken,
        Instant expiresAt,
        String username,
        String role,
        Integer sedeId,
        String sedeName, // nullable — no Sede assigned
        String fullName, // AD full name (username fallback); any cosmetic greeting override is client-local
        String dni,      // nullable — directory unreachable at login, or no DNI on record
        List<String> permissions) {
}
