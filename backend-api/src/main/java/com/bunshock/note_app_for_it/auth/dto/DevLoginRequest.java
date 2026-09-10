package com.bunshock.note_app_for_it.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for the dev-only {@code POST /api/v1/auth/dev-login} — the seeded {@code APP_USER} to mint a session for. */
public record DevLoginRequest(@NotBlank String username) {
}
