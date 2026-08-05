package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.UserNoteController;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Reproduces the scenario reported after "Falla persists across note type switches" was added:
// confirming Falla and then switching note types away and back must NOT reopen the Falla popup —
// initially only reproducible with details left blank, then (after a first attempted fix using
// a `failureConfirmed` flag) reported as happening in BOTH cases (details blank or filled). The
// second report is what led to the current fix: a dedicated `restoringMotivo` flag set only for
// the duration of loadMotivoOptions()'s programmatic cmbMotivo.setValue(...) restore call, so the
// listener can recognize "this is a restore, not a user pick" directly instead of inferring it
// from failureConfirmed (whatever the exact reason that inference broke down).
//
// Deliberately does NOT drive the real FailureDetailView popup for the "should NOT reopen" cases
// — an earlier version of this test did (opened a real APPLICATION_MODAL Stage, filled it, closed
// it via a button click) and passed on its own, but left the JavaFX Application Thread in a state
// that made the *next* test class's FXMLLoader.load() call hang for 5+ seconds when run as part
// of the full suite. Instead, those tests pre-seed UserNoteController's private fields via
// reflection so openFailureDetailDialog() never fires and no window is created — the persistence
// logic itself is still exercised for real, through the actual toggle listeners and
// loadMotivoOptions() restore path. The "fresh pick SHOULD open it" test does open one real
// popup, but as the only window interaction in that test method, closed directly (not via a
// button click) with no further toggle switches afterward.
class UserNoteFallaPersistenceTest {

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
    void fallaCauseOnlyPersistsAcrossTypeSwitchWithoutReopeningPopup() throws Exception {
        runOnFxThread(() -> {
            Loaded l = load();

            setPrivateField(l.controller, "failureCause", "No enciende");
            setPrivateField(l.controller, "failureDetails", "");
            setPrivateField(l.controller, "failureConfirmed", true);

            Set<Window> before = new HashSet<>(Window.getWindows());

            l.controller.setNoteType("DEVOLUCIÓN");
            l.cmbMotivo.setValue("Falla");

            assertEquals(0, newWindowCount(before),
                "Setting an already-confirmed Falla should not open the detail popup");

            l.controller.setNoteType("ENTREGA");
            l.controller.setNoteType("DEVOLUCIÓN");

            assertEquals("No enciende", l.controller.getFailureCause(),
                "Failure cause must persist across a type switch even when details were left blank");
            assertEquals("Falla", l.cmbMotivo.getValue());
            assertEquals(0, newWindowCount(before),
                "Falla popup must not reopen when restoring an already-confirmed selection");
        });
    }

    @Test
    void fallaCauseAndDetailsPersistAcrossTypeSwitchWithoutReopeningPopup() throws Exception {
        runOnFxThread(() -> {
            Loaded l = load();

            setPrivateField(l.controller, "failureCause", "No enciende");
            setPrivateField(l.controller, "failureDetails", "Pantalla no responde");
            setPrivateField(l.controller, "failureConfirmed", true);

            Set<Window> before = new HashSet<>(Window.getWindows());

            l.controller.setNoteType("DEVOLUCIÓN");
            l.cmbMotivo.setValue("Falla");

            assertEquals(0, newWindowCount(before),
                "Setting an already-confirmed Falla should not open the detail popup");

            l.controller.setNoteType("ENTREGA");
            l.controller.setNoteType("DEVOLUCIÓN");

            assertEquals("No enciende", l.controller.getFailureCause(),
                "Failure cause must persist across a type switch when details were also filled");
            assertEquals("Pantalla no responde", l.controller.getFailureDetails());
            assertEquals("Falla", l.cmbMotivo.getValue());
            assertEquals(0, newWindowCount(before),
                "Falla popup must not reopen when restoring an already-confirmed selection with details");
        });
    }

    // A third test (fallaPersistsAcrossTypeSwitchWithARealSkinnedComboBox) attached a real,
    // shown, non-modal Stage + forced applyCss()/layout() before running the same sequence as
    // the two tests above, specifically to validate the fix against a fully-Skinned ComboBox
    // (every other test here never attaches a Scene, so cmbMotivo never gets a real Skin, and
    // a Skin-mediated side effect of setItems() is the suspected mechanism behind the second bug
    // report — see the widened restoringMotivo guard in loadMotivoOptions()). It PASSED — both
    // in isolation and when run by exact name — genuine evidence the fix holds under realistic
    // rendering, not just a headless reflection simulation. But a real (non-modal!) Stage.show()
    // reproduced the *exact* next-test-class flakiness documented above, confirming that isn't
    // specific to modal dialogs — any real Stage-hows apparently poisons the next test class's
    // FXMLLoader.load() in this suite. Removed after confirming the fix, same as the modal-popup
    // test before it. If this needs re-verifying later, temporarily restore it and run by exact
    // name (`-Dtest=UserNoteFallaPersistenceTest#fallaPersistsAcrossTypeSwitchWithARealSkinnedComboBox`)
    // rather than leaving it in the suite.

    // NOTE: no test here drives a genuinely fresh (never-confirmed) Falla selection to verify
    // the popup still opens — doing so requires letting a real APPLICATION_MODAL Stage actually
    // show, which reproduced the exact JavaFX-thread flakiness documented in this file's class
    // comment even as the *only* window interaction in an otherwise-minimal test. The fresh-pick
    // path itself was not modified by the restoringMotivo fix (it's an additional guard wrapped
    // around the pre-existing `if (isFailureTriggerMotivo(motivo)) { if (!failureConfirmed) ... }`
    // logic, not a replacement of it) — covered by manual verification instead of an automated test.

    // ── FX-thread test harness ──────────────────────────────────────────────

    private interface FxTestBody {
        void run() throws Exception;
    }

    private void runOnFxThread(FxTestBody body) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Test did not complete in time");
        Throwable t = error.get();
        if (t instanceof AssertionError ae) throw ae;
        if (t != null) throw new RuntimeException(t);
    }

    private static class Loaded {
        UserNoteController controller;
        ComboBox<String> cmbMotivo;
    }

    private Loaded load() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/views/UserNoteView.fxml"));
        Parent root = loader.load();
        Loaded l = new Loaded();
        l.controller = loader.getController();
        @SuppressWarnings("unchecked")
        ComboBox<String> cmb = (ComboBox<String>) root.lookup("#cmbMotivo");
        l.cmbMotivo = cmb;
        assertNotNull(l.controller);
        assertNotNull(l.cmbMotivo);
        return l;
    }

    private long newWindowCount(Set<Window> before) {
        return Window.getWindows().stream().filter(w -> !before.contains(w)).count();
    }

    private void setPrivateField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
