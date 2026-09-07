package com.bunshock.note_app_for_it.auth.dto;

import java.time.Instant;
import java.util.List;

/** §2.1 — everything the session needs in one shot. */
public record LoginResponse(
        String sessionToken,
        Instant expiresAt,
        String role,
        Integer sedeId,
        String displayName,
        List<String> permissions) {
}
