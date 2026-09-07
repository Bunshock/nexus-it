package com.bunshock.note_app_for_it.rbac;

/** A row from {@code APP_USER} joined to its {@code ROLE} name — §7.1/§7.2's shape. */
public record AppUserRecord(String username, String role, Integer sedeId, boolean bypassGroupCheck) {
}
