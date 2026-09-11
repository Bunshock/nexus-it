package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.bunshock.note_app_for_it_frontend.controllers.admin.SettingsController;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.models.auth.Roles;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// AF-format (EDIT_AF_FORMAT_CONFIG) is the only field group left in this panel now — GLPI/AD/SMTP
// were all retired (see the desktop CLAUDE.md's Phase B N+6 notes), so there's no longer a
// SUPERADMIN-vs-plain-ADMIN distinction to test here; EDIT_AF_FORMAT_CONFIG is granted to both.
class SettingsControllerTest {

    private static final String[] AF_FIELD_NAMES = {"txtAfPrefix", "txtAfSeparator"};

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
        session.deactivate();
        waitForFxEvents();

        controller = new SettingsController();
        setField("txtAfPrefix", new TextField());
        setField("txtAfSeparator", new TextField());
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
        for (String name : AF_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled outside admin mode");
        }
        // btnSave is never permission-gated itself — updateFieldEditability()/handleSave() are
        // what actually enforce the check, so the button stays clickable regardless.
        assertFalse(getField("btnSave").isDisabled(), "Save button should stay enabled outside admin mode");
    }

    @Test
    void fieldsAreEnabledForPlainAdmin() throws Exception {
        session.activatePermanently(Roles.ADMIN, TestPermissions.ADMIN_GRANTS);
        updateFieldEditability();
        for (String name : AF_FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled for a plain ADMIN");
        }
        assertFalse(getField("btnSave").isDisabled(), "Save button should be enabled in admin mode");
    }

    @Test
    void fieldsAreEnabledForSuperadmin() throws Exception {
        session.activatePermanently(Roles.SUPERADMIN, TestPermissions.SUPERADMIN_GRANTS);
        updateFieldEditability();
        for (String name : AF_FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled for SUPERADMIN");
        }
    }

    @Test
    void fieldsRevertToDisabledAfterAdminModeDeactivates() throws Exception {
        session.activatePermanently(Roles.SUPERADMIN, TestPermissions.SUPERADMIN_GRANTS);
        updateFieldEditability();
        session.deactivate();
        updateFieldEditability();
        for (String name : AF_FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled again after admin mode ends");
        }
        assertFalse(getField("btnSave").isDisabled(), "Save button should stay enabled after admin mode ends");
    }
}
