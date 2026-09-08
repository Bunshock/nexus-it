package com.bunshock.note_app_for_it.common.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque, server-side, in-memory session store — backend-contract.md §2.4.
 * Not a JWT, not the IdP's own token: the middleware owns the lifetime, so
 * /auth/logout is a real revocation. Single-instance only (matches this
 * project's approved plan — move to a shared store like Redis only once a
 * second middleware instance is actually deployed, not speculatively now).
 */
@Component
public class SessionStore {

    private record Session(String username, String role, Integer sedeId, String displayName, String dni,
            Instant createdAt, Instant lastSeenAt) {
        Session touch(Instant now) {
            return new Session(username, role, sedeId, displayName, dni, createdAt, now);
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final Clock clock;

    // Explicitly @Autowired: with two constructors present (this one, plus the test-only
    // package-private one below), Spring can no longer infer which to use on its own.
    @Autowired
    public SessionStore(
            @Value("${middleware.session.idle-timeout-minutes:120}") long idleTimeoutMinutes,
            @Value("${middleware.session.absolute-timeout-minutes:720}") long absoluteTimeoutMinutes) {
        this(idleTimeoutMinutes, absoluteTimeoutMinutes, Clock.systemUTC());
    }

    /** Test-only entry point — lets timeout behavior be exercised without real sleeps. */
    SessionStore(long idleTimeoutMinutes, long absoluteTimeoutMinutes, Clock clock) {
        this.idleTimeout = Duration.ofMinutes(idleTimeoutMinutes);
        this.absoluteTimeout = Duration.ofMinutes(absoluteTimeoutMinutes);
        this.clock = clock;
    }

    /** Authorization-only session (tests, internal) — profile fields default to the username. */
    public String create(String username, String role, Integer sedeId) {
        return create(username, role, sedeId, username, null);
    }

    public String create(String username, String role, Integer sedeId, String displayName, String dni) {
        String token = randomToken();
        Instant now = clock.instant();
        sessions.put(token, new Session(username, role, sedeId, displayName, dni, now, now));
        return token;
    }

    public Instant expiresAt(String token) {
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        Instant idleExpiry = s.lastSeenAt().plus(idleTimeout);
        Instant absoluteExpiry = s.createdAt().plus(absoluteTimeout);
        return idleExpiry.isBefore(absoluteExpiry) ? idleExpiry : absoluteExpiry;
    }

    /** Validates the token, slides its idle timeout forward, and returns the caller — or empty if
     * missing/expired (either bound). An expired session is evicted, not just rejected. */
    public Optional<CallerPrincipal> validateAndTouch(String token) {
        Session s = sessions.get(token);
        if (s == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        boolean idleExpired = now.isAfter(s.lastSeenAt().plus(idleTimeout));
        boolean absoluteExpired = now.isAfter(s.createdAt().plus(absoluteTimeout));
        if (idleExpired || absoluteExpired) {
            sessions.remove(token);
            return Optional.empty();
        }
        sessions.put(token, s.touch(now));
        return Optional.of(new CallerPrincipal(s.username(), s.role(), s.sedeId(), s.displayName(), s.dni()));
    }

    public void invalidate(String token) {
        sessions.remove(token);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
