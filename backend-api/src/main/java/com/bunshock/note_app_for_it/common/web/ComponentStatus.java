package com.bunshock.note_app_for_it.common.web;

/** One dependency's health as reported by {@code GET /api/v1/status}. */
public enum ComponentStatus {
    UP,
    DOWN,
    NOT_CONFIGURED
}
