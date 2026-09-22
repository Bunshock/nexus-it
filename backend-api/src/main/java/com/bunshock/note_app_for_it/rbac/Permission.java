package com.bunshock.note_app_for_it.rbac;

/**
 * Deny-by-default: a new admin-tier action must add a value here AND a
 * {@code ROLE_PERMISSION} grant row before anyone (including ADMIN) can use
 * it — absence of a grant row IS the denial, there is no separate "denied"
 * flag to forget to set.
 *
 * <p>Trimmed from v1's 12-value set (M1, GLPI-adapter strip): local catalog
 * CRUD (MANAGE_TYPES/BRANDS/MODELS/STOCK), S/N validation, and the per-config-group
 * edit permissions (EDIT_SMTP_CONFIG/EDIT_GLPI_CONFIG/EDIT_AD_CONFIG/EDIT_AF_FORMAT_CONFIG)
 * are all gone — catalog data now comes from GLPI (read-only from this middleware's
 * perspective) and {@code PUT /config} is flat SUPERADMIN-gated instead of
 * per-field-group. {@code SYNC_GLPI} renamed {@code SYNC_EXTERNAL} (no longer
 * GLPI-specific by name, even though GLPI is the only adapter today).
 * {@code CREATE_ASSETS} is new — SUPERADMIN-only, gates the Alta de equipos flow (M7).
 */
public enum Permission {
    APPROVE_NOTES,
    SYNC_EXTERNAL,
    VALIDATE_RETURNS,
    CREATE_ASSETS
}
