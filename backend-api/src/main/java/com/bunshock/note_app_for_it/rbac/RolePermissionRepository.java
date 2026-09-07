package com.bunshock.note_app_for_it.rbac;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reads live from {@code ROLE_PERMISSION} on every check, not cached — a superadmin's
 * SQL edit (grant/revoke) must take effect immediately, not after some TTL, matching
 * this app's deny-by-default posture. The table is tiny (a handful of rows per role),
 * so this is cheap.
 */
@Repository
public class RolePermissionRepository {

    private final JdbcTemplate jdbc;

    public RolePermissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Permission> getPermissionsForRole(String role) {
        List<String> names = jdbc.queryForList(
                "SELECT rp.permission FROM ROLE_PERMISSION rp JOIN ROLE r ON r.id = rp.role_id WHERE r.name = ?",
                String.class, role);
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String name : names) {
            try {
                permissions.add(Permission.valueOf(name));
            } catch (IllegalArgumentException e) {
                // A stray/unrecognized permission string from hand-edited SQL is silently
                // ignored, not thrown — matches the desktop app's own SqliteUserRoleService
                // precedent (a grant for a Permission value that no longer exists shouldn't
                // break every permission check for that role).
            }
        }
        return permissions;
    }
}
