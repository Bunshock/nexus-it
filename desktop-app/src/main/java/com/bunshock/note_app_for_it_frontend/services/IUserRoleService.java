package com.bunshock.note_app_for_it_frontend.services;

/**
 * Login-time role lookup (ADMIN vs USER) — resolved once at login, after AD credentials and
 * group membership already passed. Not the same concern as AD group membership itself, which
 * gates whether someone can use the app at all; this only distinguishes admin vs. normal among
 * users who already got past that gate.
 *
 * Read-only by design — there is no in-app way to change a role. An admin edits the USER_ROLE
 * table directly with SQL (username PK, role column). See CLAUDE.md's "Login screen and
 * role-based admin mode" section for the reasoning.
 */
public interface IUserRoleService {

    String ROLE_ADMIN      = "ADMIN";
    String ROLE_USER       = "USER";
    /** Full ADMIN privileges, plus the Auditoría section — see the Login screen's role check. */
    String ROLE_SUPERADMIN = "SUPERADMIN";

    /** Defaults to ROLE_USER when the username has no row — most technicians are never promoted. */
    String getRole(String username);
}
