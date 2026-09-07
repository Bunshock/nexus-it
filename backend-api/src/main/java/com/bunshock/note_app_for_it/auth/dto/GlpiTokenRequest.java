package com.bunshock.note_app_for_it.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code PUT /api/v1/me/glpi-token} body — the technician's own GLPI {@code user_token}. Write-only; never returned. */
public record GlpiTokenRequest(@NotBlank String token) {
}
