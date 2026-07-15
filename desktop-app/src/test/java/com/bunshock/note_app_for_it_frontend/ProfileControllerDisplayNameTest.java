package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.bunshock.note_app_for_it_frontend.controllers.ProfileController;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Covers the "Nombre para mostrar" reset button and its new 20-char TextFormatter limit.
// TechnicianSessionServiceTest already covers setDisplayNamePreference("") reverting to the
// AD-derived fallback at the service layer; this test covers the controller wiring on top of
// it (handleResetDisplayName clearing the field and re-saving with a blank value).
class ProfileControllerDisplayNameTest {

    private static final String TEST_USERNAME = "test-profile-reset-1";

    private final TechnicianSessionService session = TechnicianSessionService.getInstance();
    private ProfileController controller;
    private TextField txtDisplayName;

    @BeforeAll
    static void initFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        runOnFx(() -> session.applyManualOverride("Rodriguez Joaquin", TEST_USERNAME, "x@x.com", "45933368"));

        controller = new ProfileController();
        txtDisplayName = new TextField();
        setField("txtProfileName", new TextField());
        setField("txtProfileUsername", new TextField());
        setField("txtProfileDni", new TextField());
        setField("txtProfileEmail", new TextField());
        setField("lblProfileStatus", new Label());
        setField("btnSave", new Button());
        setField("btnRefreshFromAd", new Button());
        setField("txtDisplayName", txtDisplayName);
        setField("lblDisplayNameStatus", new Label());
        setField("btnSaveDisplayName", new Button());
        setField("btnResetDisplayName", new Button());

        runOnFx(() -> invoke("initialize"));
    }

    @AfterEach
    void tearDown() throws Exception {
        runOnFx(() -> session.applyManualOverride(null, null, null, null));
    }

    @Test
    void textFormatterAcceptsExactly20Characters() throws Exception {
        String twenty = "a".repeat(20);
        runOnFx(() -> txtDisplayName.setText(twenty));
        assertEquals(twenty, txtDisplayName.getText());
    }

    @Test
    void textFormatterRejectsInputBeyond20Characters() throws Exception {
        runOnFx(() -> txtDisplayName.setText("a".repeat(21)));
        assertNotEquals(21, txtDisplayName.getText().length(),
            "TextFormatter should reject text longer than 20 characters");
    }

    @Test
    void resetButtonClearsFieldAndRevertsToAdFallback() throws Exception {
        runOnFx(() -> txtDisplayName.setText("Joaco"));
        runOnFx(() -> invoke("handleSaveDisplayName"));
        assertEquals("Joaco", session.getDisplayName());

        runOnFx(() -> invoke("handleResetDisplayName"));

        assertEquals("", txtDisplayName.getText());
        assertEquals("Joaquin", session.getDisplayName());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void setField(String name, Object value) throws Exception {
        Field f = ProfileController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private void invoke(String methodName) {
        try {
            Method m = ProfileController.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            m.invoke(controller);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void runOnFx(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "FX action did not complete in time");
    }
}
