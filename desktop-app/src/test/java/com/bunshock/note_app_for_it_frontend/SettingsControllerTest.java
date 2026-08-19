package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.bunshock.note_app_for_it_frontend.controllers.SettingsController;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.admin.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.admin.MockUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
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

    // EDIT_SMTP_CONFIG and EDIT_AF_FORMAT_CONFIG are deliberately SUPERADMIN-only (see
    // MockUserRoleService's default grant set) — their fields are excluded from the plain-ADMIN
    // list and checked separately.
    private static final String[] PLAIN_ADMIN_FIELD_NAMES = {
        "txtGlpiUrl", "pfGlpiApiKey", "txtAdUrl", "pfAdApiToken"
    };
    private static final String[] SUPERADMIN_ONLY_FIELD_NAMES = {
        "txtAfPrefix", "txtAfSeparator", "txtSmtpSender", "pfSmtpPassword"
    };

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
        for (String name : PLAIN_ADMIN_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled outside admin mode");
        }
        for (String name : SUPERADMIN_ONLY_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled outside admin mode");
        }
        // btnSave is never permission-gated itself — each field group is independently gated by
        // updateFieldEditability()/handleSave(), so the button stays clickable regardless.
        assertFalse(getField("btnSave").isDisabled(), "Save button should stay enabled outside admin mode");
    }

    @Test
    void plainAdminFieldsAreEnabledForPlainAdmin() throws Exception {
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        updateFieldEditability();
        for (String name : PLAIN_ADMIN_FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled for a plain ADMIN");
        }
        assertFalse(getField("btnSave").isDisabled(), "Save button should be enabled in admin mode");
    }

    @Test
    void superadminOnlyFieldsStayDisabledForPlainAdmin() throws Exception {
        // EDIT_SMTP_CONFIG and EDIT_AF_FORMAT_CONFIG are SUPERADMIN-only — this is the whole
        // point of the "prohibit-all, grant per permission" redesign: a plain ADMIN (even via a
        // real, non-fallback login) must never be able to edit these org-wide config fields.
        session.activatePermanently(IUserRoleService.ROLE_ADMIN);
        updateFieldEditability();
        for (String name : SUPERADMIN_ONLY_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should stay disabled for a plain ADMIN");
        }
    }

    @Test
    void allFieldsIncludingSmtpAreEnabledForSuperadmin() throws Exception {
        session.activatePermanently(IUserRoleService.ROLE_SUPERADMIN);
        updateFieldEditability();
        for (String name : PLAIN_ADMIN_FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled for SUPERADMIN");
        }
        for (String name : SUPERADMIN_ONLY_FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled for SUPERADMIN");
        }
    }


    @Test
    void fieldsRevertToDisabledAfterAdminModeDeactivates() throws Exception {
        session.activatePermanently(IUserRoleService.ROLE_SUPERADMIN);
        updateFieldEditability();
        session.deactivate();
        updateFieldEditability();
        for (String name : PLAIN_ADMIN_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled again after admin mode ends");
        }
        for (String name : SUPERADMIN_ONLY_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled again after admin mode ends");
        }
        assertFalse(getField("btnSave").isDisabled(), "Save button should stay enabled after admin mode ends");
    }
}
