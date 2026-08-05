package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.NoteGeneratorController;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Loads NoteGeneratorView.fxml through a real FXMLLoader — no other test does, so a typo in its
// fx:id/onAction would otherwise only surface at runtime. NoteGeneratorController.initialize()
// touches no ServiceLocator/DB state (viewFactory is only used by handlers wired here, not called
// during initialize() itself), making a full FXML load cheap and realistic — same reasoning
// PrestamoNewLoanViewFxmlTest already relies on.
class NoteGeneratorViewFxmlTest {

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
                    "/com/bunshock/note_app_for_it_frontend/views/NoteGeneratorView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("NoteGeneratorView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }

    // Regression test for the "Observaciones Generales" 300-char TextFormatter cap wired in
    // NoteGeneratorController.initialize().
    @Test
    void observationsFieldRejectsInputBeyond300Characters() throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/NoteGeneratorView.fxml"));
                loader.load();
                NoteGeneratorController controller = loader.getController();
                Field f = NoteGeneratorController.class.getDeclaredField("txtObservations");
                f.setAccessible(true);
                TextField txt = (TextField) f.get(controller);

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
    }

    // Regression test for the note-type ComboBox that replaced the old Usuario/Proveedor toggle
    // (and UserNoteView's own inner Entrega/Devolución/Préstamo/Fin de Contrato toggle stack) —
    // checks cmbNoteType resolves and lists all 5 flattened note types, and that the Observaciones
    // Generales footer resolves via fx:id.
    @Test
    void noteTypeComboBoxListsAllFiveTypesAndObservationsFooterResolves() throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/NoteGeneratorView.fxml"));
                loader.load();
                NoteGeneratorController controller = loader.getController();

                Field comboField = NoteGeneratorController.class.getDeclaredField("cmbNoteType");
                comboField.setAccessible(true);
                ComboBox<?> cmbNoteType = (ComboBox<?>) comboField.get(controller);
                assertEquals(5, cmbNoteType.getItems().size(),
                    "Entrega, Devolución, Entrega Permanente, Préstamo, Proveedor");

                Field footerField = NoteGeneratorController.class.getDeclaredField("vboxObservationsFooter");
                footerField.setAccessible(true);
                VBox footer = (VBox) footerField.get(controller);
                assertNotNull(footer);
                assertTrue(footer.isVisible(), "Footer starts visible — declared visible by default in FXML");
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
