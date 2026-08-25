package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.prestamo.PrestamoTabController;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Loads PrestamoTabContentView.fxml through a real FXMLLoader — no other test does, so a typo in
// its fx:id/onAction would otherwise only surface at runtime. PrestamoTabController.initialize()
// touches no ServiceLocator/DB state, making a full FXML load cheap and realistic — same
// reasoning PrestamoNewLoanViewFxmlTest relied on before this content moved out of the tab-bar
// shell (see NoteTabContentViewFxmlTest/RemitoTabContentViewFxmlTest for the same precedent
// applied to the other two note-creation sections).
class PrestamoTabContentViewFxmlTest {

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
                    "/com/bunshock/note_app_for_it_frontend/views/PrestamoTabContentView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("PrestamoTabContentView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }

    // Regression test for the "Observaciones Generales" 300-char TextFormatter cap wired in
    // PrestamoTabController.initialize().
    @Test
    void observationsFieldRejectsInputBeyond300Characters() throws Exception {
        AtomicReference<PrestamoTabController> controllerRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<TextField> fieldRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/PrestamoTabContentView.fxml"));
                loader.load();
                PrestamoTabController controller = loader.getController();
                controllerRef.set(controller);
                Field f = PrestamoTabController.class.getDeclaredField("txtObservations");
                f.setAccessible(true);
                TextField txt = (TextField) f.get(controller);
                fieldRef.set(txt);

                txt.setText("a".repeat(300));
                assertEquals(300, txt.getText().length());

                txt.clear();
                txt.setText("a".repeat(301));
                assertNotEquals(301, txt.getText().length(),
                    "TextFormatter should reject text longer than 300 characters");
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
        assertNotNull(controllerRef.get());
        assertNotNull(fieldRef.get());
    }

    // Regression test for the 255-char cap added to txtUserName — matches
    // NOTE_ENTREGA_DEVOLUCION.user_name's NVARCHAR(255) bound on SQL Server.
    @Test
    void userNameFieldRejectsInputBeyond255Characters() throws Exception {
        AtomicReference<TextField> fieldRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/PrestamoTabContentView.fxml"));
                loader.load();
                PrestamoTabController controller = loader.getController();
                Field f = PrestamoTabController.class.getDeclaredField("txtUserName");
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
