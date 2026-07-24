package com.bunshock.note_app_for_it_frontend;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.MockEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Mirrors UserNoteViewFxmlTest — catches fx:id/onAction typos in the S/N Validation panel's new
// filter MenuButtons (mnuSnType/Brand/Model/Active) and "Limpiar filtros" button, which a plain
// reflection-based controller test would never exercise.
class SettingsViewFxmlTest {

    @BeforeAll
    static void initFxToolkitAndConfig() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
        ConfigService.getInstance().load();
        // SettingsController.initialize() now eagerly populates the Sede combo
        // (equipmentService.getAllSedes()), which needs a real service — previously
        // setupSnTable() never dereferenced equipmentService synchronously during
        // initialize(), so this test never needed one.
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
                    "/com/bunshock/note_app_for_it_frontend/views/SettingsView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("SettingsView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }
}
