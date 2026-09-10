package com.bunshock.note_app_for_it_frontend;

import java.util.EnumSet;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.models.admin.Permission;
import com.bunshock.note_app_for_it_frontend.models.auth.Roles;

/**
 * The canonical per-role grant sets tests hand to {@code AdminSession.activatePermanently(role,
 * permissions)} — standing in for what the middleware returns in the login response. Mirrors the
 * middleware's own seed: ADMIN gets everything except {@link Permission#EDIT_SMTP_CONFIG},
 * SUPERADMIN gets everything, USER gets nothing.
 */
public final class TestPermissions {

    public static final Set<Permission> SUPERADMIN_GRANTS = EnumSet.allOf(Permission.class);
    public static final Set<Permission> ADMIN_GRANTS = EnumSet.complementOf(EnumSet.of(Permission.EDIT_SMTP_CONFIG));

    public static Set<Permission> forRole(String role) {
        if (Roles.SUPERADMIN.equals(role)) return SUPERADMIN_GRANTS;
        if (Roles.ADMIN.equals(role)) return ADMIN_GRANTS;
        return EnumSet.noneOf(Permission.class);
    }

    private TestPermissions() {}
}
