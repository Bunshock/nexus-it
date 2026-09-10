package com.bunshock.note_app_for_it.auth;

import com.bunshock.note_app_for_it.auth.dto.MeResponse;
import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.rbac.AppUserRecord;
import com.bunshock.note_app_for_it.rbac.AppUserRepository;
import com.bunshock.note_app_for_it.rbac.RolePermissionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** §7.1 — re-fetchable identity/permission snapshot, no fresh login required. */
@RestController
public class MeController {

    private final CurrentUser currentUser;
    private final AppUserRepository appUsers;
    private final RolePermissionRepository rolePermissions;

    public MeController(CurrentUser currentUser, AppUserRepository appUsers,
            RolePermissionRepository rolePermissions) {
        this.currentUser = currentUser;
        this.appUsers = appUsers;
        this.rolePermissions = rolePermissions;
    }

    @GetMapping("/api/v1/me")
    public MeResponse me() {
        CallerPrincipal caller = currentUser.require();
        Optional<AppUserRecord> record = appUsers.findByUsername(caller.username());
        List<String> permissions = rolePermissions.getPermissionsForRole(caller.role()).stream()
                .map(Enum::name).toList();
        return new MeResponse(
                caller.username(),
                caller.role(),
                caller.sedeId(),
                record.map(AppUserRecord::sedeName).orElse(null),
                caller.fullName(), // AD full name from login (username fallback if the directory
                                    // was unreachable then). The greeting override, if any, is client-local.
                caller.dni(),
                permissions,
                record.isPresent(),
                record.map(AppUserRecord::bypassGroupCheck).orElse(false));
    }
}
