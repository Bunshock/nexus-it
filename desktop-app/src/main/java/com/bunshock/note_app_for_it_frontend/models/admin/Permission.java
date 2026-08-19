package com.bunshock.note_app_for_it_frontend.models.admin;

// Deny-by-default: a new admin-tier action must add a value here AND a ROLE_PERMISSION grant
// before anyone (including ADMIN) can use it. Absence of a grant row is the denial — there is no
// separate "denied" flag to forget to set.
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
