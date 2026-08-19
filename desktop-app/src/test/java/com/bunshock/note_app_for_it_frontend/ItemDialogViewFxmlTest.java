package com.bunshock.note_app_for_it_frontend;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.catalog.MockEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.history.IHistoryService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Mirrors DatabaseSectionViewFxmlTest — catches fx:id/onAction typos. initialize() dereferences
// both equipmentService and historyService synchronously (populateTypeCombo() calls
// getMostUsedTypeNames()), so both need a real instance in ServiceLocator before load. No
// MockHistoryService exists in this codebase, so a minimal no-op stub covers the 3 non-default
// methods (getMostUsedTypeNames() itself is a default method returning an empty list).
class ItemDialogViewFxmlTest {

    @BeforeAll
    static void initFxToolkitAndConfig() throws Exception {
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
                    "/com/bunshock/note_app_for_it_frontend/views/ItemDialogView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("ItemDialogView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }
}
