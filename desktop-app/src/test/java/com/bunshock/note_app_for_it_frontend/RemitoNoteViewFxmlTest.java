package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.RemitoNoteController;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// No prior test loaded RemitoNoteView.fxml through a real FXMLLoader — same reasoning as
// UserNoteViewFxmlTest/PrestamoNewLoanViewFxmlTest. RemitoNoteController.initialize() only
// touches ConfigService/TechnicianSessionService (no ServiceLocator/DB state), making a full
// FXML load cheap and realistic.
class RemitoNoteViewFxmlTest {

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
                    "/com/bunshock/note_app_for_it_frontend/views/RemitoNoteView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("RemitoNoteView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }

    // Regression test for the Área/Sede length caps (100/200 chars) wired in
    // RemitoNoteController.initialize() — applies to both Destinatario's and Remitente's own
    // Área/Sede fields, all four real TextFormatters, not a re-implemented copy.
    @Test
    void areaAndSedeFieldsRejectInputBeyondTheirLengthCapsForBothDestinatarioAndRemitente() throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/RemitoNoteView.fxml"));
                loader.load();
                RemitoNoteController controller = loader.getController();

                assertAreaCap(controller, "txtDestinatarioArea");
                assertSedeCap(controller, "txtDestinatarioSede");
                assertAreaCap(controller, "txtRemitenteArea");
                assertSedeCap(controller, "txtRemitenteSede");
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
    }

    private void assertAreaCap(RemitoNoteController controller, String fieldName) throws Exception {
        Field field = RemitoNoteController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        TextField txt = (TextField) field.get(controller);
        txt.setText("a".repeat(100));
        assertEquals(100, txt.getText().length());
        txt.clear();
        txt.setText("a".repeat(101));
        assertNotEquals(101, txt.getText().length(),
            fieldName + ": TextFormatter should reject text longer than 100 characters");
    }

    private void assertSedeCap(RemitoNoteController controller, String fieldName) throws Exception {
        Field field = RemitoNoteController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        TextField txt = (TextField) field.get(controller);
        txt.setText("a".repeat(200));
        assertEquals(200, txt.getText().length());
        txt.clear();
        txt.setText("a".repeat(201));
        assertNotEquals(201, txt.getText().length(),
            fieldName + ": TextFormatter should reject text longer than 200 characters");
    }

    // Regression test for Remitente's manual-vs-defaulted split: Nombre starts blank (the IT
    // Support coordinator is manually entered every time, no default — see CLAUDE.md), while
    // Área/Sede start pre-filled from app-config.json's remito.remitenteArea/remitenteSede.
    @Test
    void remitenteNombreStartsBlankWhileAreaAndSedeArePreFilledFromConfig() throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/RemitoNoteView.fxml"));
                loader.load();
                RemitoNoteController controller = loader.getController();

                Field nameField = RemitoNoteController.class.getDeclaredField("txtRemitenteName");
                nameField.setAccessible(true);
                TextField txtName = (TextField) nameField.get(controller);
                assertTrue(txtName.getText().isBlank(),
                    "Remitente Nombre has no default — it's manually entered every time");

                Field areaField = RemitoNoteController.class.getDeclaredField("txtRemitenteArea");
                areaField.setAccessible(true);
                TextField txtArea = (TextField) areaField.get(controller);
                assertFalse(txtArea.getText().isBlank(),
                    "Remitente Área should be pre-filled from app-config.json's remito.remitenteArea");

                Field sedeField = RemitoNoteController.class.getDeclaredField("txtRemitenteSede");
                sedeField.setAccessible(true);
                TextField txtSede = (TextField) sedeField.get(controller);
                assertFalse(txtSede.getText().isBlank(),
                    "Remitente Sede should be pre-filled from app-config.json's remito.remitenteSede");
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
    }
}
