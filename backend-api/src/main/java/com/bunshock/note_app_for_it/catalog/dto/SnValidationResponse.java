package com.bunshock.note_app_for_it.catalog.dto;

/**
 * The active S/N regex rule for a single model — what {@code ItemDialogController} needs while a
 * technician types a serial number. {@code regexPattern} may be null (a rule row exists and is
 * active, but carries no pattern). Mirrors the desktop app's {@code SnValidation} shape.
 */
public record SnValidationResponse(int modelId, String regexPattern, boolean active) {
}
