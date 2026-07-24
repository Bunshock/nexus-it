package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

    // setSedePreference() also persists to the real local APP_SETTINGS table, same leakage
    // caveat as setDisplayNamePreference() above — use dedicated usernames and clean up. Only
    // sedePreferencePersistsAcrossRefresh() below actually needs a real SEDE catalog row (it's
    // the one test exercising loadSedePreference()'s DB-backed name resolution) — inserted and
    // hard-deleted within that single test so it never lingers in the real local catalog for
    // other technicians/tests to see; every other test only exercises the in-memory
    // setSedePreference()/getSede() round-trip, which never touches SEDE at all.

    @Test
    void getSedeNullWhenNoPreferenceSet() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-1", "x@x.com", "45933368");
        assertNull(session.getSede());
    }

    @Test
    void setSedePreferenceIsReflectedImmediately() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-2", "x@x.com", "45933368");
        try {
            session.setSedePreference(1, "Campus");
            assertEquals("Campus", session.getSede());
            assertEquals(1, session.getSedeId());
        } finally {
            session.setSedePreference(null, null);
        }
    }

    @Test
    void setSedePreferenceNullClearsIt() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-3", "x@x.com", "45933368");
        session.setSedePreference(1, "Campus");
        session.setSedePreference(null, null);
        assertNull(session.getSede());
        assertNull(session.getSedeId());
    }

    @Test
    void setSedePreferenceNoOpsWhenUsernameUnresolved() {
        session.applyManualOverride(null, null, null, null);
        session.setSedePreference(1, "Campus");
        assertNull(session.getSede());
    }

    @Test
    void sedePreferencePersistsAcrossRefresh() throws SQLException {
        // Unlike this file's other real-DB-touching tests (APP_SETTINGS existed long before
        // this feature), SEDE only exists once DatabaseService.initialize() has actually run —
        // no other test in this suite calls it against the real data/noteapp.db, so this test
        // can't assume it's already there. initialize() is safe/idempotent to call here, same
        // as every real app startup.
        DatabaseService.getInstance().initialize();
        int sedeId = insertTestSede("Campus (test)");
        try {
            session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-4", "x@x.com", "45933368");
            session.setSedePreference(sedeId, "Campus (test)");
            // Simulate a re-resolution of the same username (e.g. a later AD refresh) — the
            // preference must reload from APP_SETTINGS and re-resolve the name from SEDE, not
            // just live in memory.
            session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-4", "x@x.com", "45933368");
            assertEquals("Campus (test)", session.getSede());
            assertEquals(sedeId, session.getSedeId());
        } finally {
            session.setSedePreference(null, null);
            deleteTestSede(sedeId);
        }
    }

    @Test
    void setSedePreferenceNotifiesOnlySedeListenersNotIdentityListeners() throws Exception {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-5", "x@x.com", "45933368");
        AtomicBoolean sedeFired = new AtomicBoolean(false);
        AtomicBoolean identityFired = new AtomicBoolean(false);
        Runnable sedeListener = () -> sedeFired.set(true);
        Runnable identityListener = () -> identityFired.set(true);
        session.addOnSedeChangeListener(sedeListener);
        session.addOnChangeListener(identityListener);
        try {
            session.setSedePreference(1, "Campus");
            waitForFxEvents();
            assertTrue(sedeFired.get());
            assertFalse(identityFired.get());
        } finally {
            session.removeOnSedeChangeListener(sedeListener);
            session.removeOnChangeListener(identityListener);
            session.setSedePreference(null, null);
        }
    }

    private int insertTestSede(String name) throws SQLException {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO SEDE (name, deprecated) VALUES (?, 0)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private void deleteTestSede(int id) throws SQLException {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM SEDE WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }
}
