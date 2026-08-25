package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bunshock.note_app_for_it_frontend.controllers.note.NoteGeneratorController;
import com.bunshock.note_app_for_it_frontend.controllers.note.NoteTabController;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.catalog.MockEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Drives NoteGeneratorController's tab-bar shell directly — no Stage.show() (this suite's
// standing rule), a plain FXMLLoader on the FX thread is enough since none of this logic depends
// on a realized Skin. Loads a real NoteTabController per page (via NoteGeneratorController's own
// FXMLLoader calls), so ConfigService must be loaded first.
//
// Rewritten for the Excel-style page strip (plain ToggleButtons in pageTabsBox, StackPane
// pageStack) that replaced the real Tab/TabPane this used to drive — see
// NoteGeneratorController's own class-level comment for why. Each page's ToggleButton carries its
// NoteTabController as userData (mirroring the old Tab.setUserData(ctrl) precedent), so most
// assertions read straight off pageTabsBox's children with no need to reflect into the shell's
// private pages/NotePage bookkeeping at all — only the "close the sole/a specific page" tests
// need that, via the "Cerrar" context-menu item.
class NoteGeneratorTabLifecycleTest {

    @BeforeAll
    static void initFxToolkitAndConfig() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
        ConfigService.getInstance().load();
        // ProviderNoteController.initialize() dereferences equipmentService synchronously
        // (refreshProviders()), reached whenever a test switches a page's note type to "Proveedor
        // (Entrega)" — same setup precedent as RemitoNoteTabLifecycleTest.
        ServiceLocator.getInstance().setEquipmentService(new MockEquipmentService());
    }

    private static NoteGeneratorController loadShell(HBox[] pageTabsBoxOut) throws Exception {
        FXMLLoader loader = new FXMLLoader(NoteGeneratorTabLifecycleTest.class.getResource(
            "/com/bunshock/note_app_for_it_frontend/views/NoteGeneratorView.fxml"));
        loader.load();
        NoteGeneratorController controller = loader.getController();
        Field f = NoteGeneratorController.class.getDeclaredField("pageTabsBox");
        f.setAccessible(true);
        pageTabsBoxOut[0] = (HBox) f.get(controller);
        return controller;
    }

    private static Button addButtonOf(NoteGeneratorController controller) throws Exception {
        Field f = NoteGeneratorController.class.getDeclaredField("btnAddTab");
        f.setAccessible(true);
        return (Button) f.get(controller);
    }

    private static ToggleButton toggleAt(HBox pageTabsBox, int index) {
        return (ToggleButton) pageTabsBox.getChildren().get(index);
    }

    private static NoteTabController controllerOf(ToggleButton toggle) {
        return (NoteTabController) toggle.getUserData();
    }

    private static MenuItem closeMenuItem(ToggleButton toggle) {
        return toggle.getContextMenu().getItems().get(0);
    }

    @Test
    void startsWithExactlyOnePageWhichIsNotClosable() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];

            assertEquals(1, pageTabsBox.getChildren().size());
            ToggleButton realPage = toggleAt(pageTabsBox, 0);

            assertEquals("Entrega", realPage.getText(), "falls back to the plain note type until a name is available");
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
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];

            addButtonOf(controller).fire();

            assertEquals(2, pageTabsBox.getChildren().size());
            ToggleButton page1 = toggleAt(pageTabsBox, 0);
            ToggleButton page2 = toggleAt(pageTabsBox, 1);
            // No distinguishing number — explicit user direction. Two fresh pages with no name
            // yet both fall back to the same plain note type, and that's accepted as-is.
            assertEquals("Entrega", page1.getText());
            assertEquals("Entrega", page2.getText());
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
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            addButtonOf(controller).fire();

            ToggleButton page1 = toggleAt(pageTabsBox, 0);
            ToggleButton page2 = toggleAt(pageTabsBox, 1);

            closeMenuItem(page1).fire(); // what "Cerrar" ultimately does

            assertEquals(1, pageTabsBox.getChildren().size());
            assertSame(page2, toggleAt(pageTabsBox, 0));
            assertEquals("Entrega", page2.getText(), "label is per-page, not position-based — unaffected by the removal");
            assertTrue(closeMenuItem(page2).isDisable(), "sole remaining page becomes non-closable again");
            return null;
        });
    }

    @Test
    void closingAPageInTheMiddleLeavesSurvivingPagesUndisturbed() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            Button btnAddTab = addButtonOf(controller);
            btnAddTab.fire();
            btnAddTab.fire();
            btnAddTab.fire();

            ToggleButton originalFourthPage = toggleAt(pageTabsBox, 3);
            closeMenuItem(toggleAt(pageTabsBox, 2)).fire(); // close the third page

            assertEquals(3, pageTabsBox.getChildren().size(), "the 3 surviving pages");
            assertEquals("Entrega", toggleAt(pageTabsBox, 0).getText());
            assertEquals("Entrega", toggleAt(pageTabsBox, 1).getText());
            assertSame(originalFourthPage, toggleAt(pageTabsBox, 2),
                "the old fourth page object survives — no renumbering to reassign it to");
            assertEquals("Entrega", toggleAt(pageTabsBox, 2).getText());
            return null;
        });
    }

    @Test
    void closingTheSoleRemainingPageReplacesItWithAFreshOneRatherThanLeavingZero() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
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
    void changingAPagesNoteTypeUpdatesItsOwnLabelWhenNoNameIsSetYet() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            ToggleButton realPage = toggleAt(pageTabsBox, 0);

            assertEquals("Entrega", realPage.getText(), "defaults to the first note type option");

            NoteTabController ctrl = controllerOf(realPage);
            Field f = NoteTabController.class.getDeclaredField("cmbNoteType");
            f.setAccessible(true);
            ComboBox<?> cmbNoteType = (ComboBox<?>) f.get(ctrl);
            cmbNoteType.getSelectionModel().select(1); // "Devolución"

            assertEquals("Devolución", realPage.getText());
            return null;
        });
    }

    @Test
    void selectingAProviderImmediatelyShowsItsNameOnThePage() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            ToggleButton realPage = toggleAt(pageTabsBox, 0);
            NoteTabController ctrl = controllerOf(realPage);

            Field typeField = NoteTabController.class.getDeclaredField("cmbNoteType");
            typeField.setAccessible(true);
            ComboBox<?> cmbNoteType = (ComboBox<?>) typeField.get(ctrl);
            cmbNoteType.getSelectionModel().select(4); // "Proveedor (Entrega)" — last option

            assertEquals("Proveedor (Entrega)", realPage.getText(),
                "no provider picked yet — falls back to the plain type label");

            Method getProviderNoteController = NoteTabController.class.getDeclaredMethod("getProviderNoteController");
            getProviderNoteController.setAccessible(true);
            Object providerCtrl = getProviderNoteController.invoke(ctrl);
            Field comboField = providerCtrl.getClass().getDeclaredField("cmbProviderSearch");
            comboField.setAccessible(true);
            ComboBox<Object> cmbProviderSearch = (ComboBox<Object>) comboField.get(providerCtrl);

            com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentProvider provider =
                new com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentProvider(7, "TechCorp SA");
            cmbProviderSearch.setItems(javafx.collections.FXCollections.observableArrayList(provider));
            cmbProviderSearch.setValue(provider);

            assertEquals("TechCorp SA - Proveedor (Entrega)", realPage.getText(),
                "shows the selected provider's full name plus the note type — the type is never dropped");
            return null;
        });
    }

    @Test
    void adLookupSuccessShowsTheLastWordOfTheFullNameOnThePage() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            ToggleButton realPage = toggleAt(pageTabsBox, 0);
            NoteTabController ctrl = controllerOf(realPage);

            Method getUserNoteController = NoteTabController.class.getDeclaredMethod("getUserNoteController");
            getUserNoteController.setAccessible(true);
            com.bunshock.note_app_for_it_frontend.controllers.note.UserNoteController userCtrl =
                (com.bunshock.note_app_for_it_frontend.controllers.note.UserNoteController) getUserNoteController.invoke(ctrl);

            // AD returns "Apellido Nombre" order — lastWord() is what actually recovers the real
            // first name (same fix already applied to the sidebar greeting, see
            // TechnicianSessionService.getDisplayName()).
            com.bunshock.note_app_for_it_frontend.models.auth.ADUser user =
                new com.bunshock.note_app_for_it_frontend.models.auth.ADUser(
                    "12345678", "Gómez Juan", "jgomez", "jgomez@empresa.com", null);
            userCtrl.fillUserData(user);

            assertEquals("Juan - Entrega", realPage.getText(), "name plus the still-selected note type");
            return null;
        });
    }

    // Blur-driven manual-entry commit needs a real Scene/focus, out of scope for this suite —
    // testing the word-picking logic directly instead.
    @Test
    void manualNameFirstWordAndLastWordHelpersPickTheRightWord() throws Exception {
        Class<?> userCtrlClass = Class.forName(
            "com.bunshock.note_app_for_it_frontend.controllers.note.UserNoteController");
        Method firstWord = userCtrlClass.getDeclaredMethod("firstWord", String.class);
        firstWord.setAccessible(true);
        Method lastWord = userCtrlClass.getDeclaredMethod("lastWord", String.class);
        lastWord.setAccessible(true);

        assertEquals("Juan", firstWord.invoke(null, "Juan Pérez"));
        assertEquals("Ana", firstWord.invoke(null, "Ana"));
        assertNull(firstWord.invoke(null, "   "));
        assertNull(firstWord.invoke(null, (Object) null));

        assertEquals("Juan", lastWord.invoke(null, "Gómez Juan"));
        assertEquals("Ana", lastWord.invoke(null, "Ana"));
        assertNull(lastWord.invoke(null, "   "));
        assertNull(lastWord.invoke(null, (Object) null));
    }

    @Test
    void addButtonDisablesAtTheFiveTabCapAndClickingItDoesNothing() throws Exception {
        runOnFxThread(() -> {
            HBox[] pageTabsBoxOut = new HBox[1];
            NoteGeneratorController controller = loadShell(pageTabsBoxOut);
            HBox pageTabsBox = pageTabsBoxOut[0];
            Button btnAddTab = addButtonOf(controller);

            for (int i = 0; i < 4; i++) {
                btnAddTab.fire(); // grows 1 -> 5 pages
            }
            assertEquals(5, pageTabsBox.getChildren().size());
            assertTrue(btnAddTab.isDisabled(), "the cap must disable the button");

            btnAddTab.fire(); // a disabled button's fire() is a no-op, but assert defensively too

            assertEquals(5, pageTabsBox.getChildren().size(), "still 5 pages — the cap must hold");
            return null;
        });
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
