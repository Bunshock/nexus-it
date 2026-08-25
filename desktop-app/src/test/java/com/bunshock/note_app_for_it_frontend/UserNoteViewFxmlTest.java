package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.note.UserNoteController;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// No test in this suite previously loaded UserNoteView.fxml through a real FXMLLoader — every
// existing UserNoteController-adjacent test constructs the controller directly and sets @FXML
// fields via reflection, which never exercises fx:id / onAction / onMouseClicked resolution.
// A typo in any of those (e.g. a mismatched fx:id, or referencing a controller method that
// doesn't exist) only surfaces at runtime, not at compile time — this test catches that class
// of bug by actually loading the FXML and letting initialize() run for real.
class UserNoteViewFxmlTest {

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
                    "/com/bunshock/note_app_for_it_frontend/views/UserNoteView.fxml"));
                rootRef.set(loader.load());
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "FXML load did not complete in time");
        if (errorRef.get() != null) {
            fail("UserNoteView.fxml failed to load: " + errorRef.get(), errorRef.get());
        }
        assertNotNull(rootRef.get());
    }

    // Regression test for the 255-char cap added to txtUserName —
    // matches NOTE_ENTREGA_DEVOLUCION.user_name's NVARCHAR(255) bound on SQL Server. This
    // controller's initialize() touches no ServiceLocator/DB state, so a real FXML load is cheap
    // here — same rationale already established for PrestamoNewLoanViewFxmlTest's own cap test.
    @Test
    void userNameFieldRejectsInputBeyond255Characters() throws Exception {
        AtomicReference<TextField> fieldRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/UserNoteView.fxml"));
                loader.load();
                UserNoteController controller = loader.getController();
                Field f = UserNoteController.class.getDeclaredField("txtUserName");
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

    // Regression test: an incomplete AD search used to commit its partial text as the tab name
    // before the technician picked a result from the multi-match popup.
    @Test
    void manualNameCommitIsSkippedWhileAnAdSearchIsPending() throws Exception {
        AtomicReference<UserNoteController> controllerRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/UserNoteView.fxml"));
                loader.load();
                UserNoteController controller = loader.getController();
                controllerRef.set(controller);

                Field nameField = UserNoteController.class.getDeclaredField("txtUserName");
                nameField.setAccessible(true);
                TextField txtUserName = (TextField) nameField.get(controller);
                txtUserName.setText("Perez Ju"); // an incomplete/partial search query

                Field pendingField = UserNoteController.class.getDeclaredField("adSearchPending");
                pendingField.setAccessible(true);
                java.lang.reflect.Method commit = UserNoteController.class
                    .getDeclaredMethod("commitManualNameIfNotSearching");
                commit.setAccessible(true);

                pendingField.set(controller, true); // simulates a search in flight / popup open
                commit.invoke(controller);
                assertNull(controller.getTabDisplayName(),
                    "the incomplete search text must not be committed while a search is pending");

                pendingField.set(controller, false); // simulates the popup closing after a real pick
                commit.invoke(controller);
                assertEquals("Perez", controller.getTabDisplayName(),
                    "once nothing is pending, blur commits normally again");
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
    }

    // Companion test: cancelling must leave an already-committed label untouched too.
    @Test
    void cancellingTheAdSearchPopupLeavesAnAlreadyCommittedLabelUntouched() throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/bunshock/note_app_for_it_frontend/views/UserNoteView.fxml"));
                loader.load();
                UserNoteController controller = loader.getController();

                Field tabNameField = UserNoteController.class.getDeclaredField("tabDisplayName");
                tabNameField.setAccessible(true);
                tabNameField.set(controller, "Ana"); // a name already committed from a prior AD fill

                Field nameField = UserNoteController.class.getDeclaredField("txtUserName");
                nameField.setAccessible(true);
                TextField txtUserName = (TextField) nameField.get(controller);
                txtUserName.setText("Otro Nombre"); // a fresh, unrelated, incomplete search query

                Field pendingField = UserNoteController.class.getDeclaredField("adSearchPending");
                pendingField.setAccessible(true);
                pendingField.set(controller, true); // the popup is "open"

                controller.onAdSelectionDialogClosed(); // what handleCancel() ultimately triggers

                assertEquals("Ana", controller.getTabDisplayName(),
                    "cancelling must leave the label exactly as it was before the search started");
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
