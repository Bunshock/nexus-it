package com.bunshock.note_app_for_it.auth.dto;

import java.util.List;

/** §2.0 — unauthenticated discovery, lets the desktop app ship with only {@code middleware.baseUrl}. */
public record AuthConfigResponse(String issuer, String clientId, List<String> scopes, String authBackendDisplayName) {
}
