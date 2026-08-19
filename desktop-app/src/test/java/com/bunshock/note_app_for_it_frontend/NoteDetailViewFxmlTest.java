package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.history.NoteDetailController;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Added alongside the header "close" button — catches a typo in its fx:id/onAction
// (e.g. referencing a controller method that doesn't exist) that would otherwise only
// surface at runtime, since no other test loads this FXML through a real FXMLLoader.
// This popup is shared by both a regular technician and an admin (gated by the adminMode
// flag NoteDetailController.open() takes), so there is only one FXML to verify either way.
class NoteDetailViewFxmlTest {

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

        AtomicReference<NoteDetailController> controllerRef = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/NoteDetailView.fxml"));
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
            fail("NoteDetailView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());

        // Catches a typo in the approval-section VBox's fx:id — added alongside the note
        // approval workflow's Aprobar/Rechazar buttons.
        Field f = NoteDetailController.class.getDeclaredField("vboxApproval");
        f.setAccessible(true);
        assertNotNull((VBox) f.get(controllerRef.get()));
    }
}
