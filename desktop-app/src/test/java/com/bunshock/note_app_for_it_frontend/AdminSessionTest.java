package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.bunshock.note_app_for_it_frontend.services.AdminSession;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminSessionTest {

    private final AdminSession session = AdminSession.getInstance();

    @BeforeAll
    static void initFxToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
    }

    @BeforeEach
    void resetSession() throws Exception {
        session.deactivate();
        waitForFxEvents();
    }

    @AfterEach
    void cleanupSession() throws Exception {
        session.deactivate();
        waitForFxEvents();
    }

    private void setLastActivity(LocalDateTime value) throws Exception {
        Field f = AdminSession.class.getDeclaredField("lastActivity");
        f.setAccessible(true);
        f.set(session, value);
    }

    private void waitForFxEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "FX event queue did not drain in time");
    }

    @Test
    void inactiveBeforeActivation() {
        assertFalse(session.isActive());
    }

    @Test
    void activateMakesSessionActive() {
        session.activate();
        assertTrue(session.isActive());
    }

    @Test
    void deactivateMakesSessionInactive() {
        session.activate();
        session.deactivate();
        assertFalse(session.isActive());
    }

    @Test
    void refreshActivityKeepsSessionAliveWithinTimeout() throws Exception {
        session.activate();
        setLastActivity(LocalDateTime.now().minusMinutes(14));
        session.refreshActivity();
        assertTrue(session.isActive());
        assertTrue(session.getRemainingSeconds() > 14 * 60);
    }

    @Test
    void isActiveExpiresSessionAfterTimeout() throws Exception {
        session.activate();
        setLastActivity(LocalDateTime.now().minusMinutes(16));
        assertFalse(session.isActive());
    }

    @Test
    void getRemainingSecondsIsZeroWhenInactive() {
        assertEquals(0, session.getRemainingSeconds());
    }

    @Test
    void getRemainingSecondsIsPositiveWhenJustActivated() {
        session.activate();
        long remaining = session.getRemainingSeconds();
        assertTrue(remaining > 0 && remaining <= 15 * 60);
    }

    @Test
    void onActivateListenerFires() throws Exception {
        AtomicBoolean fired = new AtomicBoolean(false);
        Runnable listener = () -> fired.set(true);
        session.addOnActivateListener(listener);
        try {
            session.activate();
            waitForFxEvents();
            assertTrue(fired.get());
        } finally {
            session.removeOnActivateListener(listener);
        }
    }

    @Test
    void onDeactivateListenerFires() throws Exception {
        AtomicBoolean fired = new AtomicBoolean(false);
        Runnable listener = () -> fired.set(true);
        session.addOnDeactivateListener(listener);
        try {
            session.activate();
            waitForFxEvents();
            session.deactivate();
            waitForFxEvents();
            assertTrue(fired.get());
        } finally {
            session.removeOnDeactivateListener(listener);
        }
    }

    @Test
    void activatePermanentlyNeverExpiresEvenAfterLongInactivity() throws Exception {
        session.activatePermanently();
        setLastActivity(LocalDateTime.now().minusMinutes(9999));
        assertTrue(session.isActive());
        assertEquals(0, session.getRemainingSeconds());
    }

    @Test
    void activatePermanentlyStillDeactivatesNormally() {
        session.activatePermanently();
        session.deactivate();
        assertFalse(session.isActive());
    }

    @Test
    void plainActivateAfterPermanentlyResumesNormalExpiry() throws Exception {
        session.activatePermanently();
        session.activate();
        setLastActivity(LocalDateTime.now().minusMinutes(16));
        assertFalse(session.isActive());
    }

    @Test
    void expiryFiresBothDeactivateAndExpireListeners() throws Exception {
        AtomicBoolean deactivateFired = new AtomicBoolean(false);
        AtomicBoolean expireFired = new AtomicBoolean(false);
        Runnable onDeactivate = () -> deactivateFired.set(true);
        Runnable onExpire = () -> expireFired.set(true);
        session.addOnDeactivateListener(onDeactivate);
        session.addOnExpireListener(onExpire);
        try {
            session.activate();
            waitForFxEvents();
            setLastActivity(LocalDateTime.now().minusMinutes(16));
            assertFalse(session.isActive());
            waitForFxEvents();
            assertTrue(deactivateFired.get());
            assertTrue(expireFired.get());
        } finally {
            session.removeOnDeactivateListener(onDeactivate);
            session.removeOnExpireListener(onExpire);
        }
    }
}
