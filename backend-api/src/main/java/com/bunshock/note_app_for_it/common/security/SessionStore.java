package com.bunshock.note_app_for_it.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Opaque, server-side, in-memory session store — backend-contract.md §2.4.
 * Not a JWT, not the IdP's own token: the middleware owns the lifetime, so
 * /auth/logout is a real revocation. Single-instance only (matches this
 * project's approved plan — move to a shared store like Redis only once a
 * second middleware instance is actually deployed, not speculatively now).
 *
 * <p>Expiry is enforced two ways: lazily on {@link #validateAndTouch} (an expired
 * token is evicted, not just rejected), and proactively by {@link #scheduledSweep}
 * so an abandoned session that's never touched again doesn't linger for the process
 * lifetime. {@code max-sessions} is a last-resort ceiling against a login flood
 * between sweeps — {@link #create} prunes expired entries and, if still at capacity,
 * evicts the least-recently-used one.
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

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
    private final int maxSessions;
    private final Clock clock;

    // Explicitly @Autowired: with multiple constructors present (this one, plus the test-only
    // package-private ones below), Spring can no longer infer which to use on its own.
    @Autowired
    public SessionStore(
            @Value("${middleware.session.idle-timeout-minutes:120}") long idleTimeoutMinutes,
            @Value("${middleware.session.absolute-timeout-minutes:720}") long absoluteTimeoutMinutes,
            @Value("${middleware.session.max-sessions:50000}") int maxSessions) {
        this(idleTimeoutMinutes, absoluteTimeoutMinutes, maxSessions, Clock.systemUTC());
    }

    /** Test-only entry point — lets timeout behavior be exercised without real sleeps. */
    SessionStore(long idleTimeoutMinutes, long absoluteTimeoutMinutes, Clock clock) {
        this(idleTimeoutMinutes, absoluteTimeoutMinutes, Integer.MAX_VALUE, clock);
    }

    /** Test-only entry point with an explicit session ceiling. */
    SessionStore(long idleTimeoutMinutes, long absoluteTimeoutMinutes, int maxSessions, Clock clock) {
        this.idleTimeout = Duration.ofMinutes(idleTimeoutMinutes);
        this.absoluteTimeout = Duration.ofMinutes(absoluteTimeoutMinutes);
        this.maxSessions = maxSessions;
        this.clock = clock;
    }

    /** Authorization-only session (tests, internal) — profile fields default to the username. */
    public String create(String username, String role, Integer sedeId) {
        return create(username, role, sedeId, username, null);
    }

    public String create(String username, String role, Integer sedeId, String displayName, String dni) {
        Instant now = clock.instant();
        if (sessions.size() >= maxSessions) {
            sweep();
            if (sessions.size() >= maxSessions) {
                evictLeastRecentlyUsed();
            }
        }
        String token = randomToken();
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
        if (isExpired(s, now)) {
            sessions.remove(token);
            return Optional.empty();
        }
        sessions.put(token, s.touch(now));
        return Optional.of(new CallerPrincipal(s.username(), s.role(), s.sedeId(), s.displayName(), s.dni()));
    }

    public void invalidate(String token) {
        sessions.remove(token);
    }

    /** Current live-entry count (includes not-yet-swept expired ones). Exposed for the sweep log
     * and monitoring. */
    public int size() {
        return sessions.size();
    }

    /** Removes every expired session (idle or absolute bound). Runs on a schedule and inline from
     * {@link #create} when at the ceiling. Returns how many were evicted. */
    public int sweep() {
        Instant now = clock.instant();
        int before = sessions.size();
        sessions.values().removeIf(s -> isExpired(s, now));
        return before - sessions.size();
    }

    @Scheduled(fixedDelayString = "${middleware.session.sweep-interval-minutes:5}", timeUnit = TimeUnit.MINUTES)
    void scheduledSweep() {
        int evicted = sweep();
        if (evicted > 0) {
            log.debug("session sweep evicted {} expired session(s), {} remain", evicted, sessions.size());
        }
    }

    private boolean isExpired(Session s, Instant now) {
        return now.isAfter(s.lastSeenAt().plus(idleTimeout))
                || now.isAfter(s.createdAt().plus(absoluteTimeout));
    }

    private void evictLeastRecentlyUsed() {
        sessions.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().lastSeenAt()))
                .map(Map.Entry::getKey)
                .ifPresent(token -> {
                    sessions.remove(token);
                    log.warn("session ceiling {} reached — evicted the least-recently-used session", maxSessions);
                });
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
