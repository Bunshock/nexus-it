package com.bunshock.note_app_for_it_frontend.services;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.models.Permission;

/**
 * In-memory, test-only. Real roles/sedes/permissions are set by a superadmin directly via SQL
 * (see IUserRoleService), not through any app code — this class's setRole()/setSedeId()/
 * setPermissionsForRole() are test-arrangement helpers only, deliberately not part of
 * IUserRoleService itself. getPermissionsForRole() defaults to every Permission for ADMIN and
 * SUPERADMIN (matching this app's real default seed) and none for USER, so a test that doesn't
 * care about permissions doesn't have to arrange them.
 */
public class MockUserRoleService implements IUserRoleService {

    private final Map<String, String> roles = new LinkedHashMap<>();
    private final Map<String, Integer> sedeIds = new LinkedHashMap<>();
    private final Map<String, Set<Permission>> rolePermissions = new LinkedHashMap<>();

    public MockUserRoleService() {
        rolePermissions.put(ROLE_ADMIN, EnumSet.complementOf(EnumSet.of(Permission.EDIT_SMTP_CONFIG)));
        rolePermissions.put(ROLE_SUPERADMIN, EnumSet.allOf(Permission.class));
        rolePermissions.put(ROLE_USER, EnumSet.noneOf(Permission.class));
    }

    @Override
    public String getRole(String username) {
        return roles.getOrDefault(username, ROLE_USER);
    }

    @Override
    public boolean isRegistered(String username) {
        return roles.containsKey(username);
    }

    @Override
    public Integer getSedeId(String username) {
        return sedeIds.get(username);
    }

    @Override
    public Set<Permission> getPermissionsForRole(String role) {
        return rolePermissions.getOrDefault(role, EnumSet.noneOf(Permission.class));
    }

    /** Test-only seeding — not part of IUserRoleService, since nothing in the app sets a role. */
    public void setRole(String username, String role) {
        roles.put(username, role);
    }

    /** Test-only seeding. */
    public void setSedeId(String username, Integer sedeId) {
        sedeIds.put(username, sedeId);
    }

    /** Test-only seeding — overrides this mock's default permission set for a role. */
    public void setPermissionsForRole(String role, Set<Permission> permissions) {
        rolePermissions.put(role, permissions);
    }
}
