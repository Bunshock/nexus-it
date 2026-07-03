package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.bunshock.note_app_for_it_frontend.controllers.SettingsController;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;

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

    private static final String[] FIELD_NAMES = {
        "txtAfPrefix", "txtAfSeparator", "txtAfLength", "txtAfFiller",
        "txtSmtpSender", "pfSmtpPassword", "txtGlpiUrl", "pfGlpiApiKey"
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
        session.deactivate();
        waitForFxEvents();

        controller = new SettingsController();
        setField("txtAfPrefix", new TextField());
        setField("txtAfSeparator", new TextField());
        setField("txtAfLength", new TextField());
        setField("txtAfFiller", new TextField());
        setField("txtSmtpSender", new TextField());
        setField("pfSmtpPassword", new PasswordField());
        setField("txtGlpiUrl", new TextField());
        setField("pfGlpiApiKey", new PasswordField());
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
        for (String name : FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled outside admin mode");
        }
        assertTrue(getField("btnSave").isDisabled(), "Save button should be disabled outside admin mode");
    }

    @Test
    void fieldsAreEnabledWhenAdminModeActive() throws Exception {
        session.activate();
        updateFieldEditability();
        for (String name : FIELD_NAMES) {
            assertFalse(getField(name).isDisabled(), name + " should be enabled in admin mode");
        }
        assertFalse(getField("btnSave").isDisabled(), "Save button should be enabled in admin mode");
    }

    @Test
    void fieldsRevertToDisabledAfterAdminModeDeactivates() throws Exception {
        session.activate();
        updateFieldEditability();
        session.deactivate();
        updateFieldEditability();
        for (String name : FIELD_NAMES) {
            assertTrue(getField(name).isDisabled(), name + " should be disabled again after admin mode ends");
        }
        assertTrue(getField("btnSave").isDisabled(), "Save button should be disabled again after admin mode ends");
    }
}
