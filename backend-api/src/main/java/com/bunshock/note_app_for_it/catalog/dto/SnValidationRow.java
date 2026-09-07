package com.bunshock.note_app_for_it.catalog.dto;

/**
 * One row of the S/N Validation admin panel — every active asset-type model, LEFT-joined to its
 * (optional) rule. {@code regexPattern == null} / {@code active == false} both mean "no rule set
 * for this model". Ported from the desktop app's {@code SnValidationRow}.
 */
public record SnValidationRow(int modelId, String typeName, String brandName, String modelName,
        String regexPattern, boolean active) {
}
