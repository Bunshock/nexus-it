package com.bunshock.note_app_for_it_frontend.models.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /api/v1/status} — per-dependency health for the sidebar/connectivity indicator.
 * Each value is {@code "UP"}, {@code "DOWN"}, or {@code "NOT_CONFIGURED"}; kept as strings since
 * the UI only maps them to a dot colour and a label.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MiddlewareStatus(String middleware, String database, String directory) {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
}
