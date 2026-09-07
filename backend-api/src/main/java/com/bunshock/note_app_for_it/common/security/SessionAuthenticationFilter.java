package com.bunshock.note_app_for_it.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Resolves {@code Authorization: Bearer <sessionToken>} into a {@link CallerPrincipal} —
 * backend-contract.md §2.2. Leaves the request unauthenticated (never throws) on a
 * missing/invalid/expired token; {@code SecurityConfig}'s entry point is what turns
 * that into the actual 401 for a protected path, so a public path (e.g. /auth/config)
 * still works with no token presented at all.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionStore sessionStore;

    public SessionAuthenticationFilter(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            Optional<CallerPrincipal> caller = sessionStore.validateAndTouch(token);
            caller.ifPresent(principal -> {
                var auth = new UsernamePasswordAuthenticationToken(principal, token, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
