package com.bunshock.note_app_for_it.auth.dto;

import java.util.List;

/** §7.1 — re-fetchable without a fresh login, lets the app refresh its UI gate mid-session. */
public record MeResponse(
        String username,
        String role,
        Integer sedeId,
        String fullName, // AD full name (username fallback); any cosmetic greeting override is client-local
        List<String> permissions,
        boolean registered,
        boolean bypassGroupCheck) {
}
