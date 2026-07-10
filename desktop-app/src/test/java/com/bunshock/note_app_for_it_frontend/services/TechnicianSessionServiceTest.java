package com.bunshock.note_app_for_it_frontend.services;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TechnicianSessionServiceTest {

    private final TechnicianSessionService session = TechnicianSessionService.getInstance();

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
        session.applyManualOverride(null, null, null, null);
        waitForFxEvents();
    }

    @AfterEach
    void cleanupSession() throws Exception {
        session.applyManualOverride(null, null, null, null);
        waitForFxEvents();
    }

    private void waitForFxEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "FX event queue did not drain in time");
    }

    @Test
    void deriveUsernameFromEmailStripsDomain() {
        assertEquals("lmantovani", TechnicianSessionService.deriveUsernameFromEmail("lmantovani@ues21.edu.ar"));
    }

    @Test
    void deriveUsernameFromEmailReturnsAsIsWhenNoAtSign() {
        assertEquals("lmantovani", TechnicianSessionService.deriveUsernameFromEmail("lmantovani"));
    }

    @Test
    void applyManualOverrideSetsAllFieldsAndClearsError() {
        session.applyManualOverride("Marcos Tecnico", "mtecnico", "mtecnico@ues21.edu.ar", "27555111");

        assertEquals("Marcos Tecnico", session.getName());
        assertEquals("mtecnico", session.getUsername());
        assertEquals("mtecnico@ues21.edu.ar", session.getEmail());
        assertEquals("27555111", session.getDni());
        assertNull(session.getLastError());
        assertTrue(session.isResolved());
    }

    @Test
    void applyManualOverrideSetsUpdateSourceToManual() {
        session.applyManualOverride("Marcos Tecnico", "mtecnico", "mtecnico@ues21.edu.ar", "27555111");
        assertEquals(TechnicianSessionService.UpdateSource.MANUAL, session.getLastUpdateSource());
    }

    @Test
    void isResolvedFalseWhenNameIsNull() {
        session.applyManualOverride(null, null, null, null);
        assertFalse(session.isResolved());
    }

    @Test
    void applyManualOverrideNotifiesListeners() throws Exception {
        AtomicBoolean fired = new AtomicBoolean(false);
        Runnable listener = () -> fired.set(true);
        session.addOnChangeListener(listener);
        try {
            session.applyManualOverride("Ana Diaz", "adiaz", "adiaz@ues21.edu.ar", "28999111");
            waitForFxEvents();
            assertTrue(fired.get());
        } finally {
            session.removeOnChangeListener(listener);
        }
    }

    // setDisplayNamePreference() persists to the real local APP_SETTINGS table (keyed by
    // username), not just in-memory — so tests must use usernames no other test touches and
    // must clear whatever they write, or leftover rows leak across test runs and into the
    // real app's database on this machine.

    @Test
    void getDisplayNameFallsBackToLastWordOfFullNameWhenNoPreferenceSet() {
        // AD's name order is kept as "Apellido Nombre" (see AdApiService.normalizeName()), so
        // the last word is the given name — the greeting should use that, not the surname.
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-fallback-1", "x@x.com", "45933368");
        assertEquals("Joaquin", session.getDisplayName());
    }

    @Test
    void getDisplayNameCapitalizesFallback() {
        session.applyManualOverride("rodriguez joaquin", "test-tss-fallback-2", "x@x.com", "45933368");
        assertEquals("Joaquin", session.getDisplayName());
    }

    @Test
    void getDisplayNameNullWhenNameUnresolved() {
        session.applyManualOverride(null, null, null, null);
        assertNull(session.getDisplayName());
    }

    @Test
    void setDisplayNamePreferenceOverridesFallbackImmediately() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-pref-1", "x@x.com", "45933368");
        try {
            session.setDisplayNamePreference("Joaco");
            assertEquals("Joaco", session.getDisplayName());
        } finally {
            session.setDisplayNamePreference("");
        }
    }

    @Test
    void setDisplayNamePreferenceBlankClearsOverrideBackToFallback() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-pref-2", "x@x.com", "45933368");
        session.setDisplayNamePreference("Joaco");
        session.setDisplayNamePreference("   ");
        assertEquals("Joaquin", session.getDisplayName());
    }

    @Test
    void setDisplayNamePreferenceNoOpsWhenUsernameUnresolved() {
        session.applyManualOverride(null, null, null, null);
        session.setDisplayNamePreference("Joaco");
        assertNull(session.getDisplayName());
    }
}
