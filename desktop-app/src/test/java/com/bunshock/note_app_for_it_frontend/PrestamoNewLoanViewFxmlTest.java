package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.PrestamoNewLoanController;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Loads PrestamoNewLoanView.fxml ("Cargar Nuevo Préstamo") through a real FXMLLoader — no other
// test does, so a typo in its fx:id/onAction would otherwise only surface at runtime.
class PrestamoNewLoanViewFxmlTest {

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
                    "/com/bunshock/note_app_for_it_frontend/views/PrestamoNewLoanView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("PrestamoNewLoanView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }

    // Regression test for the "Observaciones Generales" 300-char TextFormatter cap wired in
    // PrestamoNewLoanController.initialize() — exercised through a real FXMLLoader (not manual
    // field injection) since this controller's initialize() touches no ServiceLocator/DB state,
    // making a full FXML load cheap and realistic here (see PendingCountsService-adjacent
    // controllers in CLAUDE.md for why that's NOT true of every controller in this app).
    @Test
    void observationsFieldRejectsInputBeyond300Characters() throws Exception {
        AtomicReference<PrestamoNewLoanController> controllerRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<TextField> fieldRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/PrestamoNewLoanView.fxml"));
                loader.load();
                PrestamoNewLoanController controller = loader.getController();
                controllerRef.set(controller);
                Field f = PrestamoNewLoanController.class.getDeclaredField("txtObservations");
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

    // Regression test for the 255-char cap added to txtUserName —
    // matches NOTE_ENTREGA_DEVOLUCION.user_name's NVARCHAR(255) bound on SQL Server. Same
    // real-FXML-load rationale as the Observaciones test above.
    @Test
    void userNameFieldRejectsInputBeyond255Characters() throws Exception {
        AtomicReference<TextField> fieldRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/PrestamoNewLoanView.fxml"));
                loader.load();
                PrestamoNewLoanController controller = loader.getController();
                Field f = PrestamoNewLoanController.class.getDeclaredField("txtUserName");
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
