package com.bunshock.note_app_for_it_frontend;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.IHistoryService;
import com.bunshock.note_app_for_it_frontend.services.MockEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Loads RemitoHistoryView.fxml through a real FXMLLoader — catches fx:id/onAction typos that
// would otherwise only surface at runtime. initialize() dereferences equipmentService and
// historyService synchronously (initSedeMenu()/loadEnvios()), same as
// DatabaseSectionViewFxmlTest/SettingsViewFxmlTest, so ServiceLocator needs real services before
// load.
class RemitoHistoryViewFxmlTest {

    @BeforeAll
    static void initFxToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
        ServiceLocator.getInstance().setEquipmentService(new MockEquipmentService());
        ServiceLocator.getInstance().setHistoryService(new IHistoryService() {
            @Override public int save(NoteReport report) { return 0; }
            @Override public List<NoteReport> getAll() { return List.of(); }
            @Override public NoteReport getById(int id) { return null; }
        });
    }

    @Test
    void loadsWithoutFxmlBindingErrors() throws Exception {
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/RemitoHistoryView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("RemitoHistoryView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }
}
