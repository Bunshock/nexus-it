package com.bunshock.note_app_for_it.common.web;

/**
 * {@code GET /api/v1/status} body — a per-dependency health snapshot for the desktop client's
 * connectivity indicator. {@code middleware} is {@code UP} by definition (you got a response).
 * {@code database} is a {@code SELECT 1}. {@code directory} is a light probe of the AD API, or
 * {@code NOT_CONFIGURED} when {@code middleware.directory.*} is unset.
 */
public record StatusResponse(
        ComponentStatus middleware,
        ComponentStatus database,
        ComponentStatus directory) {
}
