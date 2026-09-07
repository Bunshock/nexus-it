package com.bunshock.note_app_for_it.rbac;

/**
 * Deny-by-default: a new admin-tier action must add a value here AND a
 * {@code ROLE_PERMISSION} grant row before anyone (including ADMIN) can use
 * it — absence of a grant row IS the denial, there is no separate "denied"
 * flag to forget to set. Ported verbatim from the desktop app's
 * {@code models.admin.Permission} (same 12 values — this middleware v1 has
 * no external adapter, so the catalog/config screens these gate are staying
 * in the app rather than being removed the way the full GLPI-shaped
 * contract's trimmed 3-permission enum assumed).
 */
public enum Permission {
    MANAGE_TYPES,
    MANAGE_BRANDS,
    MANAGE_MODELS,
    MANAGE_STOCK,
    EDIT_SN_VALIDATION,
    EDIT_SMTP_CONFIG,
    EDIT_GLPI_CONFIG,
    EDIT_AD_CONFIG,
    EDIT_AF_FORMAT_CONFIG,
    APPROVE_NOTES,
    SYNC_GLPI,
    VALIDATE_RETURNS
}
