package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.prestamo.PrestamoNewLoanController;
import com.bunshock.note_app_for_it_frontend.controllers.prestamo.PrestamoTabController;
import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Drives PrestamoNewLoanController's tab-bar shell directly — no Stage.show() (this suite's
// standing rule), a plain FXMLLoader on the FX thread is enough since none of this logic depends
// on a realized Skin. Loads a real PrestamoTabController per page (via PrestamoNewLoanController's
// own FXMLLoader calls); its initialize() touches no ServiceLocator/DB state, so — unlike
// RemitoNoteTabLifecycleTest — no MockEquipmentService setup is needed, mirroring
// NoteGeneratorTabLifecycleTest exactly.
//
// Rewritten for the Excel-style page strip (plain ToggleButtons in pageTabsBox, StackPane
// pageStack) that replaced the real Tab/TabPane this used to drive — see
// NoteGeneratorController's class-level comment (PrestamoNewLoanController mirrors its shape
// exactly) for why. Each page's ToggleButton carries its PrestamoTabController as userData
// (mirroring the old Tab.setUserData(ctrl) precedent).
class PrestamoNewLoanTabLifecycleTest {

    @BeforeAll
    static void initFxToolkitAndConfig() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
        ConfigService.getInstance().load();
    }

    private static PrestamoNewLoanController loadShell(HBox[] pageTabsBoxOut) throws Exception {
        FXMLLoader loader = new FXMLLoader(PrestamoNewLoanTabLifecycleTest.class.getResource(
            "/com/bunshock/note_app_for_it_frontend/views/PrestamoNewLoanView.fxml"));
        loader.load();
        PrestamoNewLoanController controller = loader.getController();
        Field f = PrestamoNewLoanController.class.getDeclaredField("pageTabsBox");
        f.setAccessible(true);
        pageTabsBoxOut[0] = (HBox) f.get(controller);
        return controller;
    }

    private static Button addButtonOf(PrestamoNewLoanController controller) throws Exception {
        Field f = PrestamoNewLoanController.class.getDeclaredField("btnAddTab");
        f.setAccessible(true);
        return (Button) f.get(controller);
    }

    private static ToggleButton toggleAt(HBox pageTabsBox, int index) {
        return (ToggleButton) pageTabsBox.getChildren().get(index);
    }

    private static PrestamoTabController controllerOf(ToggleButton toggle) {
        return (PrestamoTabController) toggle.getUserData();
    }

    private static MenuItem closeMenuItem(ToggleButton toggle) {
        return toggle.getContextMenu().getItems().get(0);
    }

    @Test
    void startsWithExactlyOnePageWhichIsNotClosable() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            PrestamoNewLoanController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];

            assertEquals(1, pageTabsBox.getChildren().size());
            ToggleButton realPage = toggleAt(pageTabsBox, 0);

            assertEquals("Préstamo", realPage.getText(), "falls back to the plain word until a name is available");
            assertTrue(closeMenuItem(realPage).isDisable(), "sole remaining page must not be closable");
            assertFalse(addButtonOf(controller).isDisabled(), "well under the cap");
            assertTrue(realPage.isSelected());
            return null;
        });
    }

    @Test
    void clickingAddTabCreatesAndSelectsANewPageAndBothPagesBecomeClosable() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            PrestamoNewLoanController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];

            addButtonOf(controller).fire();

            assertEquals(2, pageTabsBox.getChildren().size());
            ToggleButton page1 = toggleAt(pageTabsBox, 0);
            ToggleButton page2 = toggleAt(pageTabsBox, 1);
            // No distinguishing number — explicit user direction. Two fresh pages with no name
            // yet both fall back to the same plain word, and that's accepted as-is.
            assertEquals("Préstamo", page1.getText());
            assertEquals("Préstamo", page2.getText());
            assertTrue(page2.isSelected(), "clicking \"+\" must land on the newly created page");
            assertFalse(closeMenuItem(page1).isDisable());
            assertFalse(closeMenuItem(page2).isDisable());
            return null;
        });
    }

    @Test
    void closingOneOfTwoPagesMakesTheSurvivorNonClosableAgain() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            PrestamoNewLoanController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            addButtonOf(controller).fire();

            ToggleButton page1 = toggleAt(pageTabsBox, 0);
            ToggleButton page2 = toggleAt(pageTabsBox, 1);

            closeMenuItem(page1).fire(); // what "Cerrar" ultimately does

            assertEquals(1, pageTabsBox.getChildren().size());
            assertSame(page2, toggleAt(pageTabsBox, 0));
            assertEquals("Préstamo", page2.getText(), "label is per-page, not position-based — unaffected by the removal");
            assertTrue(closeMenuItem(page2).isDisable(), "sole remaining page becomes non-closable again");
            return null;
        });
    }

    @Test
    void closingAPageInTheMiddleLeavesSurvivingPagesUndisturbed() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            PrestamoNewLoanController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            Button btnAddTab = addButtonOf(controller);
            btnAddTab.fire();
            btnAddTab.fire();
            btnAddTab.fire();

            ToggleButton originalFourthPage = toggleAt(pageTabsBox, 3);
            closeMenuItem(toggleAt(pageTabsBox, 2)).fire(); // close the third page

            assertEquals(3, pageTabsBox.getChildren().size(), "the 3 surviving pages");
            assertEquals("Préstamo", toggleAt(pageTabsBox, 0).getText());
            assertEquals("Préstamo", toggleAt(pageTabsBox, 1).getText());
            assertSame(originalFourthPage, toggleAt(pageTabsBox, 2),
                "the old fourth page object survives — no renumbering to reassign it to");
            assertEquals("Préstamo", toggleAt(pageTabsBox, 2).getText());
            return null;
        });
    }

    @Test
    void closingTheSoleRemainingPageReplacesItWithAFreshOneRatherThanLeavingZero() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            PrestamoNewLoanController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            ToggleButton onlyRealPage = toggleAt(pageTabsBox, 0);

            // Only reachable in practice via the settings-driven auto-close-after-generation path
            // (a manual "Cerrar" click can't reach it — the sole page's menu item is disabled),
            // so this test fires it directly, same bypass the old Tab-based test used.
            closeMenuItem(onlyRealPage).fire();

            assertEquals(1, pageTabsBox.getChildren().size(), "a fresh replacement page");
            ToggleButton replacement = toggleAt(pageTabsBox, 0);
            assertNotSame(onlyRealPage, replacement);
            assertTrue(closeMenuItem(replacement).isDisable());
            assertTrue(replacement.isSelected());
            return null;
        });
    }

    @Test
    void addButtonDisablesAtTheFiveTabCapAndClickingItDoesNothing() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            PrestamoNewLoanController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            Button btnAddTab = addButtonOf(controller);

            for (int i = 0; i < 4; i++) {
                btnAddTab.fire(); // grows 1 -> 5 pages
            }
            assertEquals(5, pageTabsBox.getChildren().size());
            assertTrue(btnAddTab.isDisabled(), "the cap must disable the button");

            btnAddTab.fire();

            assertEquals(5, pageTabsBox.getChildren().size(), "still 5 pages — the cap must hold");
            return null;
        });
    }

    @Test
    void adLookupSuccessShowsTheLastWordOfTheFullNameOnThePage() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            PrestamoNewLoanController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            ToggleButton realPage = toggleAt(pageTabsBox, 0);
            PrestamoTabController ctrl = controllerOf(realPage);

            // AD returns "Apellido Nombre" order — lastWord() is what actually recovers the real
            // first name (same fix already applied to the sidebar greeting, see
            // TechnicianSessionService.getDisplayName()).
            ADUser user = new ADUser("12345678", "Gómez Juan", "jgomez", "jgomez@empresa.com", null);
            ctrl.fillUserData(user);

            assertEquals("Juan", realPage.getText());
            return null;
        });
    }

    @Test
    void manualNameFirstWordHelperPicksTheFirstSpaceSeparatedWord() throws Exception {
        Method m = PrestamoTabController.class.getDeclaredMethod("firstWord", String.class);
        m.setAccessible(true);
        assertEquals("Juan", m.invoke(null, "Juan Pérez"));
        assertEquals("Ana", m.invoke(null, "Ana"));
        assertNull(m.invoke(null, "   "));
        assertNull(m.invoke(null, (Object) null));
    }

    private interface FxAction { Void run() throws Exception; }

    private static void runOnFxThread(FxAction action) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "FX action did not complete in time");
        if (errorRef.get() != null) {
            fail("Assertion failed on FX thread: " + errorRef.get(), errorRef.get());
        }
    }
}
