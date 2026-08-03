package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.bunshock.note_app_for_it_frontend.controllers.SettingsController;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.MockUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsControllerTest {

    // EDIT_SMTP_CONFIG is deliberately SUPERADMIN-only (see MockUserRoleService's default grant
    // set) — SMTP fields are excluded from this shared list and checked separately.
    private static final String[] NON_SMTP_FIELD_NAMES = {
        "txtAfPrefix", "txtAfSeparator", "txtGlpiUrl", "pfGlpiApiKey", "txtAdUrl", "pfAdApiToken"
    };
    private static final String[] SMTP_FIELD_NAMES = { "txtSmtpSender", "pfSmtpPassword" };

    private final AdminSession session = AdminSession.getInstance();
    private SettingsController controller;

    @BeforeAll
    static void initFxToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ServiceLocator.getInstance().setUserRoleService(new MockUserRoleService());
        session.deactivate();
        waitForFxEvents();

        controller = new SettingsController();
        setField("txtAfPrefix", new TextField());
        setField("txtAfSeparator", new TextField());
        setField("txtSmtpSender", new TextField());
        setField("pfSmtpPassword", new PasswordField());
        setField("txtGlpiUrl", new TextField());
        setField("pfGlpiApiKey", new PasswordField());
        setField("txtAdUrl", new TextField());
        setField("pfAdApiToken", new PasswordField());
        setField("btnSave", new Button());
    }

    @AfterEach
    void tearDown() throws Exception {
        session.deactivate();
        waitForFxEvents();
    }

    private void setField(String name, Object value) throws Exception {
        Field f = SettingsController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private Control getField(String name) throws Exception {
        Field f = SettingsController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Control) f.get(controller);
    }

    private void updateFieldEditability() throws Exception {
        Method m = SettingsController.class.getDeclaredMethod("updateFieldEditability");
        m.setAccessible(true);
        m.invoke(controller);
    }

    private void waitForFxEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "FX event queue did not drain in time");
    }

    @Test
    void fieldsAreDisabledWhenAdminModeInactive() throws Exception {
        updateFieldEditability();
        for (String name : NON_SMTP_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled outside admin mode");
        }
        for (String name : SMTP_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled outside admin mode");
        }
        // btnSave is never permission-gated itself — each field group is independently gated by
        // updateFieldEditability()/handleSave(), so the button stays clickable regardless.
        assertFalse(getField("btnSave").isDisabled(), "Save button should stay enabled outside admin mode");
    }

    @Test
    void nonSmtpFieldsAreEnabledForPlainAdmin() throws Exception {
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        updateFieldEditability();
        for (String name : NON_SMTP_FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled for a plain ADMIN");
        }
        assertFalse(getField("btnSave").isDisabled(), "Save button should be enabled in admin mode");
    }

    @Test
    void smtpFieldsStayDisabledForPlainAdmin() throws Exception {
        // EDIT_SMTP_CONFIG is SUPERADMIN-only — this is the whole point of the "prohibit-all,
        // grant per permission" redesign: a plain ADMIN (even via a real, non-fallback login)
        // must never be able to edit SMTP credentials.
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        updateFieldEditability();
        for (String name : SMTP_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should stay disabled for a plain ADMIN");
        }
    }

    @Test
    void allFieldsIncludingSmtpAreEnabledForSuperadmin() throws Exception {
        session.activatePermanently(IUserRoleService.ROLE_SUPERADMIN);
        updateFieldEditability();
        for (String name : NON_SMTP_FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled for SUPERADMIN");
        }
        for (String name : SMTP_FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled for SUPERADMIN");
        }
    }

    @Test
    void smtpFieldsStayDisabledUnderTheSharedPasswordFallback() throws Exception {
        // session.activate() is the shared-password fallback (DatabaseSectionController's
        // requirePermission()/promptPassword() path) — it always resolves to ROLE_ADMIN, never
        // SUPERADMIN, so SMTP must stay locked even when the fallback succeeds.
        session.activate();
        updateFieldEditability();
        for (String name : SMTP_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should stay disabled under the shared-password fallback");
        }
    }

    @Test
    void fieldsRevertToDisabledAfterAdminModeDeactivates() throws Exception {
        session.activatePermanently(IUserRoleService.ROLE_SUPERADMIN);
        updateFieldEditability();
        session.deactivate();
        updateFieldEditability();
        for (String name : NON_SMTP_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled again after admin mode ends");
        }
        for (String name : SMTP_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled again after admin mode ends");
        }
        assertFalse(getField("btnSave").isDisabled(), "Save button should stay enabled after admin mode ends");
    }
}
