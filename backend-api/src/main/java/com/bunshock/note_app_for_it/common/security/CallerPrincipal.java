package com.bunshock.note_app_for_it.common.security;

/**
 * The authenticated caller, resolved from the opaque session token on every
 * request — backend-contract.md §2.5's identity invariant: the acting user
 * is always the token subject, never a request parameter.
 *
 * <p>{@code username}/{@code role}/{@code sedeId} drive authorization.
 * {@code displayName}/{@code dni} are the directory-resolved profile snapshot
 * taken at login (§7.6 / the desktop app's {@code TechnicianSessionService}) —
 * used for the greeting and for stamping {@code technician_name}/{@code technician_dni}
 * onto a note. Both fall back to the username / {@code null} when the directory
 * was unreachable at login.
 */
public record CallerPrincipal(String username, String role, Integer sedeId,
        String displayName, String dni) {

    /** Authorization-only construction (tests, internal call sites that don't carry a profile). */
    public CallerPrincipal(String username, String role, Integer sedeId) {
        this(username, role, sedeId, username, null);
    }

    public boolean isSuperadmin() {
        return "SUPERADMIN".equals(role);
    }
}
