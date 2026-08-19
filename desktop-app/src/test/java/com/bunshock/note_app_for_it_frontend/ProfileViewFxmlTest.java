package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.core.ProfileController;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Mirrors UserNoteViewFxmlTest — catches fx:id/onAction typos in the new "Nombre para mostrar"
// reset button (btnResetDisplayName / handleResetDisplayName), which a plain reflection-based
// controller test would never exercise.
class ProfileViewFxmlTest {

    @BeforeAll
    static void initFxToolkitAndConfig() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
        ConfigService.getInstance().load();
    }

    @Test
    void loadsWithoutFxmlBindingErrors() throws Exception {
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/ProfileView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("ProfileView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }

    // Regression test for the 255-char cap added to txtProfileName
    // — matches NOTE_REPORT.technician_name's NVARCHAR(255) bound on SQL Server.
    @Test
    void profileNameFieldRejectsInputBeyond255Characters() throws Exception {
        AtomicReference<TextField> fieldRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/ProfileView.fxml"));
                loader.load();
                ProfileController controller = loader.getController();
                Field f = ProfileController.class.getDeclaredField("txtProfileName");
                f.setAccessible(true);
                TextField txt = (TextField) f.get(controller);
                fieldRef.set(txt);

                txt.setText("a".repeat(255));
                assertEquals(255, txt.getText().length());

                txt.clear();
                txt.setText("a".repeat(256));
                assertNotEquals(256, txt.getText().length(),
                    "TextFormatter should reject text longer than 255 characters");
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("Assertion failed on FX thread: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(fieldRef.get());
    }
}
