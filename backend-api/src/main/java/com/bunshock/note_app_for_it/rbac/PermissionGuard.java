package com.bunshock.note_app_for_it.rbac;

import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement of backend-contract.md §7.5's H1–H4 — every guarded action
 * checks here, explicitly, rather than relying on what the (untrusted) client renders.
 * Mirrors the desktop app's {@code AdminSession.hasPermission(Permission[, Integer
 * noteSedeId])} pattern: a plain ADMIN is implicitly scoped to their own {@code sedeId}
 * (H2), SUPERADMIN bypasses the fence (H3).
 */
@Component
public class PermissionGuard {

    private final RolePermissionRepository rolePermissions;

    public PermissionGuard(RolePermissionRepository rolePermissions) {
        this.rolePermissions = rolePermissions;
    }

    /** H1: throws 403 unless the caller's role currently has this permission granted. */
    public void require(CallerPrincipal caller, Permission permission) {
        if (!rolePermissions.getPermissionsForRole(caller.role()).contains(permission)) {
            throw ApiException.forbidden("PERMISSION_DENIED",
                    "No tiene permiso para realizar esta acción.");
        }
    }

    /**
     * H1 + H2/H3: same permission check, plus — unless the caller is SUPERADMIN — the
     * target's {@code sedeId} must match the caller's own. {@code targetSedeId == null}
     * never matches for a plain ADMIN (an unset Sede is a fail-safe non-match, same as
     * the desktop app's {@code noteSedeIdOrNull()} convention), even if the caller's own
     * Sede also happens to be unset.
     */
    public void requireSedeScoped(CallerPrincipal caller, Permission permission, Integer targetSedeId) {
        require(caller, permission);
        if (caller.isSuperadmin()) {
            return;
        }
        if (targetSedeId == null || caller.sedeId() == null || !targetSedeId.equals(caller.sedeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SEDE_MISMATCH",
                    "Esta nota pertenece a otra Sede.");
        }
    }
}
