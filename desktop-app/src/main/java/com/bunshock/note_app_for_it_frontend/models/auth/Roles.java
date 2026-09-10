package com.bunshock.note_app_for_it_frontend.models.auth;

/**
 * The three role names the middleware assigns (APP_USER.role). Was previously constants on the
 * now-retired {@code IUserRoleService} — role/Sede/permissions all come from the login response
 * in Phase B, there is no client-side role service any more.
 */
public final class Roles {

    public static final String USER = "USER";
    public static final String ADMIN = "ADMIN";
    public static final String SUPERADMIN = "SUPERADMIN";

    private Roles() {}
}
