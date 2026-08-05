package com.bunshock.note_app_for_it_frontend;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.bunshock.note_app_for_it_frontend.models.Permission;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.MockUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// The old shared-password activate() (a separate, timed activation mode with a 15-minute
// inactivity expiry) was removed entirely — see CLAUDE.md. activatePermanently(role) is now the
// only way to activate a session (real ADMIN/SUPERADMIN logins only), and never expires on
// inactivity.
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
        ServiceLocator.getInstance().setUserRoleService(new MockUserRoleService());
        session.deactivate();
        waitForFxEvents();
    }

    @AfterEach
    void cleanupSession() throws Exception {
        session.deactivate();
        waitForFxEvents();
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
    void activatePermanentlyMakesSessionActive() {
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        assertTrue(session.isActive());
    }

    @Test
    void deactivateMakesSessionInactive() {
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        session.deactivate();
        assertFalse(session.isActive());
    }

    @Test
    void refreshActivityIsANoOpAndDoesNotThrow() {
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        session.refreshActivity();
        assertTrue(session.isActive());
    }

    @Test
    void onActivateListenerFires() throws Exception {
        AtomicBoolean fired = new AtomicBoolean(false);
        Runnable listener = () -> fired.set(true);
        session.addOnActivateListener(listener);
        try {
            session.activatePermanently(IUserRoleService.ROLE_ADMIN);
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
            session.activatePermanently(IUserRoleService.ROLE_ADMIN);
            waitForFxEvents();
            session.deactivate();
            waitForFxEvents();
            assertTrue(fired.get());
        } finally {
            session.removeOnDeactivateListener(listener);
        }
    }

    @Test
    void activatePermanentlyNeverExpiresEvenAfterCheckingRepeatedly() {
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        for (int i = 0; i < 5; i++) {
            assertTrue(session.isActive());
        }
    }

    @Test
    void activatePermanentlyEffectiveRoleMatchesLoggedInRole() {
        session.activatePermanently(IUserRoleService.ROLE_SUPERADMIN);
        assertEquals(IUserRoleService.ROLE_SUPERADMIN, session.getEffectiveRole());
    }

    @Test
    void effectiveRoleIsNullWhenInactive() {
        assertNull(session.getEffectiveRole());
    }

    @Test
    void effectiveRoleIsClearedOnDeactivate() {
        session.activatePermanently(IUserRoleService.ROLE_SUPERADMIN);
        session.deactivate();
        assertNull(session.getEffectiveRole());
    }

    @Test
    void hasPermissionIsFalseWhenInactive() {
        assertFalse(session.hasPermission(Permission.MANAGE_TYPES));
    }

    @Test
    void hasPermissionReflectsGrantedRolePermissions() {
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        assertTrue(session.hasPermission(Permission.MANAGE_TYPES));
        assertFalse(session.hasPermission(Permission.EDIT_SMTP_CONFIG));
    }

    @Test
    void sedeScopedHasPermissionAllowsSuperadminRegardlessOfSede() {
        session.activatePermanently(IUserRoleService.ROLE_SUPERADMIN);
        assertTrue(session.hasPermission(Permission.APPROVE_NOTES, 999));
        assertTrue(session.hasPermission(Permission.APPROVE_NOTES, (Integer) null));
    }

    @Test
    void sedeScopedHasPermissionDeniesAdminWhenNoteSedeIsNull() {
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        assertFalse(session.hasPermission(Permission.APPROVE_NOTES, (Integer) null));
    }

    @Test
    void clearListenersForLogoutRemovesEveryActivateAndDeactivateListener() throws Exception {
        AtomicBoolean activateFired = new AtomicBoolean(false);
        AtomicBoolean deactivateFired = new AtomicBoolean(false);
        session.addOnActivateListener(() -> activateFired.set(true));
        session.addOnDeactivateListener(() -> deactivateFired.set(true));

        session.clearListenersForLogout();

        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        waitForFxEvents();
        session.deactivate();
        waitForFxEvents();

        assertFalse(activateFired.get(), "clearListenersForLogout() must remove activate listeners");
        assertFalse(deactivateFired.get(), "clearListenersForLogout() must remove deactivate listeners");
    }
}
