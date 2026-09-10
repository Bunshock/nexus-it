package com.bunshock.note_app_for_it.rbac;

/**
 * A row from {@code APP_USER} joined to its {@code ROLE} name and (nullable) {@code SEDE} name —
 * §7.1/§7.2's shape. {@code sedeName} is resolved here so the login/{@code /me} responses can hand
 * the client a display name for the sidebar without a second round-trip.
 */
public record AppUserRecord(String username, String role, Integer sedeId, String sedeName,
        boolean bypassGroupCheck) {
}
