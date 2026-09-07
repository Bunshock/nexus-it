package com.bunshock.note_app_for_it.common.security;

import com.bunshock.note_app_for_it.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Reads the {@link CallerPrincipal} the {@link SessionAuthenticationFilter} attached to this request. */
@Component
public class CurrentUser {

    public CallerPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CallerPrincipal principal)) {
            // Should not normally be reachable — SecurityConfig requires authentication
            // on every path this is called from — but fail loud rather than NPE if it is.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "No hay sesión activa.");
        }
        return principal;
    }
}
