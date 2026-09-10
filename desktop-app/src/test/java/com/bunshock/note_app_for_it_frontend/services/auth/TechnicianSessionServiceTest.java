package com.bunshock.note_app_for_it_frontend.services.auth;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.models.auth.Roles;
import com.bunshock.note_app_for_it_frontend.models.auth.SessionInfo;

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
        session.clearSessionForLogout();
        waitForFxEvents();
    }

    @AfterEach
    void cleanupSession() throws Exception {
        session.clearSessionForLogout();
        waitForFxEvents();
    }

    private void waitForFxEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "FX event queue did not drain in time");
    }

    /** A middleware login response, the way LoginController hands it to loginResolved(). */
    private static SessionInfo sessionInfo(String fullName, String username, String dni, String role,
            Integer sedeId, String sedeName) {
        return new SessionInfo("tok", "2099-01-01T00:00:00Z", username, role, sedeId, sedeName,
                fullName, dni, List.of());
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
    void loginResolvedSetsIdentityRoleAndUpdateSourceAd() {
        session.loginResolved(sessionInfo("Marcos Tecnico", "mtecnico", "27555111", Roles.ADMIN, 4, "Casa Central"));

        assertEquals("Marcos Tecnico", session.getName());
        assertEquals("mtecnico", session.getUsername());
        assertNull(session.getEmail()); // the middleware session doesn't carry email
        assertEquals("27555111", session.getDni());
        assertEquals(Roles.ADMIN, session.getRole());
        assertEquals(4, session.getSedeId());
        assertEquals("Casa Central", session.getSede());
        assertNull(session.getLastError());
        assertEquals(TechnicianSessionService.UpdateSource.AD, session.getLastUpdateSource());
    }

    @Test
    void refreshProfileFromAdUpdatesNameEmailDniButNotUsernameOrRole() {
        session.loginResolved(sessionInfo("Marcos Tecnico", "mtecnico", "27555111", Roles.ADMIN, null, null));

        ADUser refreshed = new ADUser("27555112", "Marcos T. Tecnico", "mtecnico", "new@ues21.edu.ar", null);
        session.refreshProfileFromAd(refreshed);

        assertEquals("Marcos T. Tecnico", session.getName());
        assertEquals("new@ues21.edu.ar", session.getEmail());
        assertEquals("27555112", session.getDni());
        assertEquals("mtecnico", session.getUsername());
        assertEquals(Roles.ADMIN, session.getRole());
    }

    @Test
    void reportProfileRefreshErrorLeavesExistingSessionIntact() {
        session.loginResolved(sessionInfo("Marcos Tecnico", "mtecnico", "27555111", Roles.USER, null, null));

        session.reportProfileRefreshError("No se pudo conectar con Active Directory.");

        assertEquals("No se pudo conectar con Active Directory.", session.getLastError());
        // A refresh failure must not force a re-login — the already-resolved identity stays.
        assertEquals("Marcos Tecnico", session.getName());
        assertEquals("mtecnico", session.getUsername());
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

    @Test
    void clearSessionForLogoutResetsAllFieldsAndRemovesEveryListener() throws Exception {
        session.loginResolved(sessionInfo("Marcos Tecnico", "mtecnico", "27555111", Roles.ADMIN, 4, "Casa Central"));

        AtomicBoolean changeFired = new AtomicBoolean(false);
        AtomicBoolean displayNameFired = new AtomicBoolean(false);
        session.addOnChangeListener(() -> changeFired.set(true));
        session.addOnDisplayNameChangeListener(() -> displayNameFired.set(true));

        session.clearSessionForLogout();

        assertNull(session.getName());
        assertNull(session.getUsername());
        assertNull(session.getEmail());
        assertNull(session.getDni());
        assertNull(session.getRole());
        assertNull(session.getSedeId());
        assertNull(session.getSede());
        assertFalse(session.isResolved());

        // Both listener lists must be wiped too — a fresh MainController/ProfileController
        // registers its own listeners again after the next login, so anything left over here
        // would be a stale reference to a discarded controller instance.
        session.applyManualOverride("Ana Diaz", "adiaz", "adiaz@ues21.edu.ar", "28999111");
        waitForFxEvents();
        session.setDisplayNamePreference("Ana");
        waitForFxEvents();
        session.setDisplayNamePreference(""); // clean up the persisted preference this test wrote
        waitForFxEvents();

        assertFalse(changeFired.get(), "clearSessionForLogout() must remove onChangeListeners");
        assertFalse(displayNameFired.get(), "clearSessionForLogout() must remove onDisplayNameChangeListeners");
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

    @Test
    void isAutoClearFormAfterGenerationDefaultsToTrueWhenNoPreferenceSet() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-autoclear-1", "x@x.com", "45933368");
        assertTrue(session.isAutoClearFormAfterGeneration());
    }

    @Test
    void setAutoClearFormAfterGenerationPersistsAndOverridesDefault() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-autoclear-2", "x@x.com", "45933368");
        try {
            session.setAutoClearFormAfterGeneration(false);
            assertFalse(session.isAutoClearFormAfterGeneration());
        } finally {
            session.setAutoClearFormAfterGeneration(true);
        }
    }

    @Test
    void autoClearFormAfterGenerationPreferencePersistsAcrossRefresh() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-autoclear-3", "x@x.com", "45933368");
        try {
            session.setAutoClearFormAfterGeneration(false);
            // Simulate a fresh resolution of the same technician (e.g. a later login) — the
            // preference must be reloaded from APP_SETTINGS, not just held in memory.
            session.applyManualOverride("Rodriguez Joaquin", "test-tss-autoclear-3", "x@x.com", "45933368");
            assertFalse(session.isAutoClearFormAfterGeneration());
        } finally {
            session.setAutoClearFormAfterGeneration(true);
        }
    }

    @Test
    void isAutoCloseTabAfterGenerationDefaultsToFalseWhenNoPreferenceSet() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-autoclosetab-1", "x@x.com", "45933368");
        assertFalse(session.isAutoCloseTabAfterGeneration());
    }

    @Test
    void setAutoCloseTabAfterGenerationPersistsAndOverridesDefault() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-autoclosetab-2", "x@x.com", "45933368");
        try {
            session.setAutoCloseTabAfterGeneration(true);
            assertTrue(session.isAutoCloseTabAfterGeneration());
        } finally {
            session.setAutoCloseTabAfterGeneration(false);
        }
    }

    @Test
    void autoCloseTabAfterGenerationPreferencePersistsAcrossRefresh() {
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-autoclosetab-3", "x@x.com", "45933368");
        try {
            session.setAutoCloseTabAfterGeneration(true);
            // Simulate a fresh resolution of the same technician (e.g. a later login) — the
            // preference must be reloaded from APP_SETTINGS, not just held in memory.
            session.applyManualOverride("Rodriguez Joaquin", "test-tss-autoclosetab-3", "x@x.com", "45933368");
            assertTrue(session.isAutoCloseTabAfterGeneration());
        } finally {
            session.setAutoCloseTabAfterGeneration(false);
        }
    }

    // Sede now comes straight from the middleware login response (SessionInfo.sedeId / .sedeName),
    // resolved server-side from APP_USER.sede_id. There is no client-side lookup any more.

    @Test
    void sedeComesFromTheLoginResponse() {
        session.loginResolved(sessionInfo("Rodriguez Joaquin", "jrodriguez", "45933368", Roles.ADMIN, 7, "Sucursal Norte"));
        assertEquals(7, session.getSedeId());
        assertEquals("Sucursal Norte", session.getSede());
    }

    @Test
    void sedeNullWhenTheLoginResponseHasNoneAssigned() {
        session.loginResolved(sessionInfo("Rodriguez Joaquin", "jrodriguez", "45933368", Roles.USER, null, null));
        assertNull(session.getSedeId());
        assertNull(session.getSede());
    }

    @Test
    void manualOverrideDoesNotTouchSede() {
        session.loginResolved(sessionInfo("Rodriguez Joaquin", "jrodriguez", "45933368", Roles.ADMIN, 7, "Sucursal Norte"));
        session.applyManualOverride("Otro Nombre", "jrodriguez", "x@x.com", "11222333");
        assertEquals(7, session.getSedeId());
        assertEquals("Sucursal Norte", session.getSede());
    }
}
