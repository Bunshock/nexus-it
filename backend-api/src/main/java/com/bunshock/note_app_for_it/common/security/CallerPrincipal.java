package com.bunshock.note_app_for_it.common.security;

/**
 * The authenticated caller, resolved from the opaque session token on every
 * request — backend-contract.md §2.5's identity invariant: the acting user
 * is always the token subject, never a request parameter.
 */
public record CallerPrincipal(String username, String role, Integer sedeId) {

    public boolean isSuperadmin() {
        return "SUPERADMIN".equals(role);
    }
}
