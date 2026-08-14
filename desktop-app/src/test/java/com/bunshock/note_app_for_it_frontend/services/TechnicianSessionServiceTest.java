package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.bunshock.note_app_for_it_frontend.models.ADUser;

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
    void loginResolvedSetsIdentityRoleAndUpdateSourceAd() {
        ADUser user = new ADUser("27555111", "Marcos Tecnico", "mtecnico", "mtecnico@ues21.edu.ar", null);
        session.loginResolved(user, IUserRoleService.ROLE_ADMIN);

        assertEquals("Marcos Tecnico", session.getName());
        assertEquals("mtecnico", session.getUsername());
        assertEquals("mtecnico@ues21.edu.ar", session.getEmail());
        assertEquals("27555111", session.getDni());
        assertEquals(IUserRoleService.ROLE_ADMIN, session.getRole());
        assertNull(session.getLastError());
        assertEquals(TechnicianSessionService.UpdateSource.AD, session.getLastUpdateSource());
    }

    @Test
    void refreshProfileFromAdUpdatesNameEmailDniButNotUsernameOrRole() {
        ADUser original = new ADUser("27555111", "Marcos Tecnico", "mtecnico", "mtecnico@ues21.edu.ar", null);
        session.loginResolved(original, IUserRoleService.ROLE_ADMIN);

        ADUser refreshed = new ADUser("27555112", "Marcos T. Tecnico", "mtecnico", "new@ues21.edu.ar", null);
        session.refreshProfileFromAd(refreshed);

        assertEquals("Marcos T. Tecnico", session.getName());
        assertEquals("new@ues21.edu.ar", session.getEmail());
        assertEquals("27555112", session.getDni());
        assertEquals("mtecnico", session.getUsername());
        assertEquals(IUserRoleService.ROLE_ADMIN, session.getRole());
    }

    @Test
    void reportProfileRefreshErrorLeavesExistingSessionIntact() {
        ADUser user = new ADUser("27555111", "Marcos Tecnico", "mtecnico", "mtecnico@ues21.edu.ar", null);
        session.loginResolved(user, IUserRoleService.ROLE_USER);

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
        session.loginResolved(new ADUser("27555111", "Marcos Tecnico", "mtecnico", "mtecnico@ues21.edu.ar", null),
            IUserRoleService.ROLE_ADMIN);

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

    // Sede is no longer a self-service preference — it's assigned by a superadmin directly via
    // SQL against APP_USER.sede_id, and resolved here (loadAssignedSede()) at login/manual-
    // override time by reading IUserRoleService.getSedeId(username), same as role itself. Tests
    // arrange the assignment via MockUserRoleService.setSedeId() rather than calling any
    // TechnicianSessionService setter directly — there isn't one anymore.

    @Test
    void getSedeNullWhenNoneAssigned() {
        ServiceLocator.getInstance().setUserRoleService(new MockUserRoleService());
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-1", "x@x.com", "45933368");
        assertNull(session.getSede());
        assertNull(session.getSedeId());
    }

    @Test
    void getSedeNullWhenUsernameUnresolved() {
        ServiceLocator.getInstance().setUserRoleService(new MockUserRoleService());
        session.applyManualOverride(null, null, null, null);
        assertNull(session.getSede());
        assertNull(session.getSedeId());
    }

    @Test
    void assignedSedeIsResolvedFromUserRoleServiceOnManualOverride() throws SQLException {
        // Unlike this file's other real-DB-touching tests (APP_SETTINGS existed long before
        // this feature), SEDE only exists once DatabaseService.initialize() has actually run —
        // no other test in this suite calls it against the real data/noteapp.db, so this test
        // can't assume it's already there. initialize() is safe/idempotent to call here, same
        // as every real app startup.
        DatabaseService.getInstance().initialize();
        int sedeId = insertTestSede("Campus (test)");
        MockUserRoleService mockRoles = new MockUserRoleService();
        ServiceLocator.getInstance().setUserRoleService(mockRoles);
        try {
            mockRoles.setSedeId("test-tss-sede-2", sedeId);
            session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-2", "x@x.com", "45933368");
            assertEquals("Campus (test)", session.getSede());
            assertEquals(sedeId, session.getSedeId());
        } finally {
            deleteTestSede(sedeId);
        }
    }

    @Test
    void assignedSedeIsReResolvedOnEveryRefresh() throws SQLException {
        DatabaseService.getInstance().initialize();
        int sedeId = insertTestSede("Campus (test 2)");
        MockUserRoleService mockRoles = new MockUserRoleService();
        ServiceLocator.getInstance().setUserRoleService(mockRoles);
        try {
            session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-3", "x@x.com", "45933368");
            assertNull(session.getSede());

            // Simulate a superadmin assigning a Sede between two refreshes of the same
            // username (e.g. a later "Actualizar Perfil desde AD" or re-login) — the session
            // must re-resolve from IUserRoleService each time, not cache the first result.
            mockRoles.setSedeId("test-tss-sede-3", sedeId);
            session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-3", "x@x.com", "45933368");
            assertEquals("Campus (test 2)", session.getSede());
            assertEquals(sedeId, session.getSedeId());
        } finally {
            deleteTestSede(sedeId);
        }
    }

    @Test
    void getSedeNullWhenUserRoleServiceThrows() {
        ServiceLocator.getInstance().setUserRoleService(new IUserRoleService() {
            @Override public String getRole(String username) { throw new RuntimeException("unreachable"); }
            @Override public boolean isRegistered(String username) { throw new RuntimeException("unreachable"); }
            @Override public Integer getSedeId(String username) { throw new RuntimeException("unreachable"); }
            @Override public java.util.Set<com.bunshock.note_app_for_it_frontend.models.Permission>
                getPermissionsForRole(String role) { throw new RuntimeException("unreachable"); }
            @Override public boolean hasGroupCheckBypass(String username) { throw new RuntimeException("unreachable"); }
        });
        session.applyManualOverride("Rodriguez Joaquin", "test-tss-sede-4", "x@x.com", "45933368");
        assertNull(session.getSede());
        assertNull(session.getSedeId());
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
