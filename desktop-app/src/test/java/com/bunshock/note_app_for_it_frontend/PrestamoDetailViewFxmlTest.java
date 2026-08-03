package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.PrestamoDetailController;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Loads PrestamoDetailView.fxml (the Préstamo return-status detail popup) through a real
// FXMLLoader — no other test does, so a typo in its fx:id/onAction would otherwise only
// surface at runtime.
class PrestamoDetailViewFxmlTest {

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

        AtomicReference<PrestamoDetailController> controllerRef = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/PrestamoDetailView.fxml"));
                rootRef.set(loader.load());
                controllerRef.set(loader.getController());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("PrestamoDetailView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());

        // Catches a typo in the approval-section VBox's fx:id — added alongside the note
        // approval workflow's Aprobar/Rechazar buttons.
        Field f = PrestamoDetailController.class.getDeclaredField("vboxApproval");
        f.setAccessible(true);
        assertNotNull((VBox) f.get(controllerRef.get()));
    }
}
