package com.bunshock.note_app_for_it.common.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    private static Clock fixedClockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void createThenValidateReturnsTheSameCaller() {
        Instant t0 = Instant.parse("2026-09-04T10:00:00Z");
        SessionStore store = new SessionStore(120, 720, fixedClockAt(t0));

        String token = store.create("jperez", "ADMIN", 3);
        Optional<CallerPrincipal> caller = store.validateAndTouch(token);

        assertTrue(caller.isPresent());
        assertEquals("jperez", caller.get().username());
        assertEquals("ADMIN", caller.get().role());
        assertEquals(3, caller.get().sedeId());
    }

    @Test
    void unknownTokenIsEmpty() {
        SessionStore store = new SessionStore(120, 720, Clock.systemUTC());
        assertTrue(store.validateAndTouch("does-not-exist").isEmpty());
    }

    @Test
    void idleTimeoutExpiresAnUntouchedSession() {
        Instant t0 = Instant.parse("2026-09-04T10:00:00Z");
        TestClock clock = new TestClock(t0);
        SessionStore store = new SessionStore(30, 720, clock);
        String token = store.create("jperez", "USER", null);

        clock.advanceMinutes(31); // past the 30-minute idle timeout, well under the 720 absolute cap

        assertTrue(store.validateAndTouch(token).isEmpty());
        // Expired sessions are evicted, not just rejected — a second call must also miss.
        assertTrue(store.validateAndTouch(token).isEmpty());
    }

    @Test
    void activityBeforeIdleTimeoutSlidesTheWindowForward() {
        Instant t0 = Instant.parse("2026-09-04T10:00:00Z");
        TestClock clock = new TestClock(t0);
        SessionStore store = new SessionStore(30, 720, clock);
        String token = store.create("jperez", "USER", null);

        clock.advanceMinutes(20); // inside the idle window
        assertTrue(store.validateAndTouch(token).isPresent(), "touch at t+20m should still be valid");

        clock.advanceMinutes(20); // t+40m from creation, but only 20m since the last touch above
        assertTrue(store.validateAndTouch(token).isPresent(),
                "sliding idle timeout means this should still be valid — 20m since last activity, not 40m");
    }

    @Test
    void absoluteTimeoutExpiresEvenAContinuouslyActiveSession() {
        Instant t0 = Instant.parse("2026-09-04T10:00:00Z");
        TestClock clock = new TestClock(t0);
        SessionStore store = new SessionStore(120, 60, clock); // idle 2h, absolute cap 1h
        String token = store.create("jperez", "USER", null);

        clock.advanceMinutes(30);
        assertTrue(store.validateAndTouch(token).isPresent()); // still within the 1h absolute cap

        clock.advanceMinutes(35); // t+65m from creation — past the absolute cap even though idle
                                   // never elapsed 2h between touches
        assertTrue(store.validateAndTouch(token).isEmpty());
    }

    @Test
    void invalidateRemovesTheSessionImmediately() {
        SessionStore store = new SessionStore(120, 720, Clock.systemUTC());
        String token = store.create("jperez", "USER", null);
        assertTrue(store.validateAndTouch(token).isPresent());

        store.invalidate(token);

        assertTrue(store.validateAndTouch(token).isEmpty());
    }

    @Test
    void sweepEvictsExpiredSessionsWithoutAnyoneTouchingThem() {
        Instant t0 = Instant.parse("2026-09-04T10:00:00Z");
        TestClock clock = new TestClock(t0);
        SessionStore store = new SessionStore(30, 720, clock);
        store.create("a", "USER", null);
        store.create("b", "USER", null);
        store.create("c", "USER", null);
        assertEquals(3, store.size());

        clock.advanceMinutes(31); // past the idle bound for all three

        assertEquals(3, store.sweep(), "all three expired sessions evicted");
        assertEquals(0, store.size());
    }

    @Test
    void sweepLeavesStillLiveSessionsAlone() {
        Instant t0 = Instant.parse("2026-09-04T10:00:00Z");
        TestClock clock = new TestClock(t0);
        SessionStore store = new SessionStore(30, 720, clock);
        String stale = store.create("stale", "USER", null);
        String live = store.create("live", "USER", null);

        clock.advanceMinutes(20);
        store.validateAndTouch(live);   // slides 'live' forward, 'stale' untouched
        clock.advanceMinutes(15);        // stale: 35m idle (expired); live: 15m since touch (ok)

        assertEquals(1, store.sweep());
        assertTrue(store.validateAndTouch(stale).isEmpty());
        assertTrue(store.validateAndTouch(live).isPresent());
    }

    @Test
    void createEvictsTheLeastRecentlyUsedSessionWhenAtTheCeiling() {
        Instant t0 = Instant.parse("2026-09-04T10:00:00Z");
        TestClock clock = new TestClock(t0);
        SessionStore store = new SessionStore(120, 720, 2, clock); // ceiling of 2, none expired

        String a = store.create("a", "USER", null);
        clock.advanceMinutes(1);
        String b = store.create("b", "USER", null);
        clock.advanceMinutes(1);
        String c = store.create("c", "USER", null); // ceiling hit — 'a' is the LRU, drops

        assertEquals(2, store.size());
        assertTrue(store.validateAndTouch(a).isEmpty(), "oldest live session evicted to make room");
        assertTrue(store.validateAndTouch(b).isPresent());
        assertTrue(store.validateAndTouch(c).isPresent());
    }

    @Test
    void createPrunesExpiredBeforeEvictingAnyLiveSessionAtTheCeiling() {
        Instant t0 = Instant.parse("2026-09-04T10:00:00Z");
        TestClock clock = new TestClock(t0);
        SessionStore store = new SessionStore(30, 720, 2, clock);

        store.create("expired", "USER", null);
        clock.advanceMinutes(31);                 // 'expired' is now past the idle bound but still in the map
        String live = store.create("live", "USER", null); // size back at the ceiling of 2
        String fresh = store.create("fresh", "USER", null); // create() sweeps 'expired' first, so no live eviction

        assertEquals(2, store.size());
        assertTrue(store.validateAndTouch(live).isPresent());
        assertTrue(store.validateAndTouch(fresh).isPresent());
    }

    /** A mutable {@link Clock} so a single test can move time forward deterministically. */
    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advanceMinutes(long minutes) {
            now = now.plusSeconds(minutes * 60);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
