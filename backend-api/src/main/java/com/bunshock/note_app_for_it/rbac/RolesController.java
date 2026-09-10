package com.bunshock.note_app_for_it.rbac;

import com.bunshock.note_app_for_it.common.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * backend-contract.md §7.3 — read a role's granted permission set. Drives the desktop
 * app's UI gate for a role other than the caller's own (the caller's own set already
 * comes back on the login response and {@code GET /me}). Read-only: {@code ROLE_PERMISSION}
 * is edited by direct SQL only. An unknown role returns {@code []} — a role with no grants
 * is a valid deny-by-default state, not a 404.
 */
@RestController
@RequestMapping("/api/v1/roles")
public class RolesController {

    private final RolePermissionRepository rolePermissions;
    private final CurrentUser currentUser;

    public RolesController(RolePermissionRepository rolePermissions, CurrentUser currentUser) {
        this.rolePermissions = rolePermissions;
        this.currentUser = currentUser;
    }

    @GetMapping("/{role}/permissions")
    public List<String> permissions(@PathVariable String role) {
        currentUser.require();
        return rolePermissions.getPermissionsForRole(role).stream()
                .map(Enum::name)
                .sorted()
                .toList();
    }
}
