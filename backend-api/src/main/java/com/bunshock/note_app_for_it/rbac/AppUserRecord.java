package com.bunshock.note_app_for_it.rbac;

/**
 * A row from {@code APP_USER} joined to its {@code ROLE} name — §7.1/§7.2's shape. {@code sedeId}
 * is an external id (a GLPI Location id, or a placeholder pending M3), not a local {@code SEDE}
 * FK, so {@code sedeName} cannot be resolved locally anymore and is always {@code null} until M3
 * wires up a real catalog-backed name lookup.
 */
public record AppUserRecord(String username, String role, String sedeId, String sedeName,
        boolean bypassGroupCheck) {
}
