package com.bunshock.note_app_for_it.common.security;

/**
 * The authenticated caller, resolved from the opaque session token on every
 * request — backend-contract.md §2.5's identity invariant: the acting user
 * is always the token subject, never a request parameter.
 *
 * <p>{@code username}/{@code role}/{@code sedeId} drive authorization.
 * {@code fullName}/{@code dni} are the Active Directory identity snapshot taken
 * at login (§7.6 / the desktop app's {@code TechnicianSessionService}): the real
 * AD full name + DNI, or the username / {@code null} when the directory was
 * unreachable at login. {@code fullName} is what gets stamped onto a note as
 * {@code technician_name}, and it's also returned to the client as the default
 * name to greet with — but any <em>cosmetic</em> greeting override ("call me X")
 * is a desktop-client-local preference and never reaches the middleware.
 */
public record CallerPrincipal(String username, String role, Integer sedeId,
        String fullName, String dni) {

    /** Authorization-only construction (tests, internal call sites that don't carry a profile). */
    public CallerPrincipal(String username, String role, Integer sedeId) {
        this(username, role, sedeId, username, null);
    }

    public boolean isSuperadmin() {
        return "SUPERADMIN".equals(role);
    }
}
