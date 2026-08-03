package com.bunshock.note_app_for_it_frontend.services;

import java.util.Set;

import com.bunshock.note_app_for_it_frontend.models.Permission;

/**
 * Login-time role/sede/permission lookup — resolved once at login, after AD credentials and
 * group membership already passed. Not the same concern as AD group membership itself, which
 * gates whether someone can use the app at all; this only distinguishes admin tiers among users
 * who already got past that gate.
 *
 * Read-only by design — there is no in-app way to change a role, sede assignment, or permission
 * grant. A superadmin edits the APP_USER/ROLE_PERMISSION tables directly with SQL. See
 * CLAUDE.md's "Login screen and role-based admin mode" / role-based permissions sections.
 */
public interface IUserRoleService {

    String ROLE_ADMIN      = "ADMIN";
    String ROLE_USER       = "USER";
    String ROLE_SUPERADMIN = "SUPERADMIN";

    /** Defaults to ROLE_USER when the username has no row — most technicians are never promoted. */
    String getRole(String username);

    /** Whether this username has an APP_USER row at all — the actual app-access gate checked at
     * login (see LoginController), independent of role. A valid AD account with no row here must
     * not be allowed to log in at all; getRole()'s ROLE_USER default is a permission fallback for
     * already-registered accounts, not an implicit "anyone may use the app" signal. */
    boolean isRegistered(String username);

    /** The superadmin-assigned Sede id for this user, or null if not yet assigned — mandatory
     * before the user can generate any note, and (for ADMIN role) the scope a Sede-restricted
     * permission check is compared against. */
    Integer getSedeId(String username);

    /** Every permission granted to this role — a permission not in this set is denied, not
     * merely unset; there is no separate "explicitly denied" state. */
    Set<Permission> getPermissionsForRole(String role);
}
