package com.bunshock.note_app_for_it.catalog.dto;

import jakarta.validation.constraints.Size;

/**
 * Upsert body for a model's S/N rule. {@code regexPattern} is optional — blank/null clears the
 * pattern while leaving (or creating) the rule row. The 500-char cap mirrors the desktop app's
 * {@code SN_REGEX_MAX_LENGTH} and the {@code SN_VALIDATION.regex_pattern NVARCHAR(500)} column;
 * regex *syntax* is checked server-side in {@code CatalogRepository} (bean validation can't).
 */
public record SnValidationUpdateRequest(@Size(max = 500) String regexPattern, boolean active) {
}
