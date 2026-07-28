package com.bunshock.note_app_for_it_frontend.services;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory, test-only. Real roles are set by an admin directly via SQL (see
 * IUserRoleService), not through any app code — this class's setRole() is a test-arrangement
 * helper only, deliberately not part of IUserRoleService itself.
 */
public class MockUserRoleService implements IUserRoleService {

    private final Map<String, String> roles = new LinkedHashMap<>();

    @Override
    public String getRole(String username) {
        return roles.getOrDefault(username, ROLE_USER);
    }

    /** Test-only seeding — not part of IUserRoleService, since nothing in the app sets a role. */
    public void setRole(String username, String role) {
        roles.put(username, role);
    }
}
