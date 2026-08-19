package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.note.ProviderNoteController;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.catalog.MockEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// No prior test loaded ProviderNoteView.fxml through a real FXMLLoader. initialize() eagerly
// calls refreshProviders() -> ServiceLocator.getInstance().getEquipmentService().getAllProviders()
// synchronously, so — same precedent as SettingsViewFxmlTest/DatabaseSectionViewFxmlTest —
// ServiceLocator needs a real (mock) equipment service configured before loading.
class ProviderNoteViewFxmlTest {

    @BeforeAll
    static void initFxToolkitAndConfig() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
        ConfigService.getInstance().load();
        ServiceLocator.getInstance().setEquipmentService(new MockEquipmentService());
    }

    @Test
    void loadsWithoutFxmlBindingErrors() throws Exception {
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/ProviderNoteView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("ProviderNoteView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }

    // Regression tests for the 255-char caps added to txtProviderResponsibleName/txtCuit
    // — match NOTE_PROVEEDOR.responsible_name/cuit's NVARCHAR(255)
    // bounds on SQL Server. txtCuit had no TextFormatter of any kind before this.
    @Test
    void responsibleNameFieldRejectsInputBeyond255Characters() throws Exception {
        assertFieldRejectsBeyond255("txtProviderResponsibleName");
    }

    @Test
    void cuitFieldRejectsInputBeyond255Characters() throws Exception {
        assertFieldRejectsBeyond255("txtCuit");
    }

    private void assertFieldRejectsBeyond255(String fieldName) throws Exception {
        AtomicReference<TextField> fieldRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/ProviderNoteView.fxml"));
                loader.load();
                ProviderNoteController controller = loader.getController();
                Field f = ProviderNoteController.class.getDeclaredField(fieldName);
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
