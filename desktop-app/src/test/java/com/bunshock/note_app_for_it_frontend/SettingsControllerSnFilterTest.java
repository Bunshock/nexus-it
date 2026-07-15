package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.bunshock.note_app_for_it_frontend.controllers.SettingsController;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;
import com.bunshock.note_app_for_it_frontend.services.MockEquipmentService;

import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Exercises the S/N Validation panel's new Tipo/Marca/Modelo/Activo MenuButton filters directly
// against MockEquipmentService's real mock-equipment.json data (asset types only: Notebook and
// Monitor) — bypassing checkbox click simulation (no live Skin needed, see
// UserNoteFallaPersistenceTest's precedent for why that's avoided in this suite) by manipulating
// the controller's private selection Sets via reflection, the same way that test pre-seeds state.
class SettingsControllerSnFilterTest {

    private SettingsController controller;
    private MenuButton mnuType, mnuBrand, mnuModel, mnuActive;

    @BeforeAll
    static void initFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        controller = new SettingsController();
        mnuType = new MenuButton();
        mnuBrand = new MenuButton();
        mnuModel = new MenuButton();
        mnuActive = new MenuButton();

        setField("equipmentService", new MockEquipmentService());
        setField("tblSnValidation", new TableView<SnValidationRow>());
        setField("colSnType", new TableColumn<SnValidationRow, String>());
        setField("colSnBrand", new TableColumn<SnValidationRow, String>());
        setField("colSnModel", new TableColumn<SnValidationRow, String>());
        setField("colSnRegex", new TableColumn<SnValidationRow, String>());
        setField("colSnActive", new TableColumn<SnValidationRow, Boolean>());
        setField("colSnEdit", new TableColumn<SnValidationRow, Void>());
        setField("mnuSnType", mnuType);
        setField("mnuSnBrand", mnuBrand);
        setField("mnuSnModel", mnuModel);
        setField("mnuSnActive", mnuActive);

        runOnFx(() -> invoke("setupSnTable"));
        runOnFx(() -> invoke("loadSnValidationData"));
    }

    @Test
    void activeFilterShowsOnlyMatchingRows() throws Exception {
        getSet("selSnActive").add("No");
        runOnFx(() -> invoke("applySnFilter"));

        @SuppressWarnings("unchecked")
        TableView<SnValidationRow> table = (TableView<SnValidationRow>) getField("tblSnValidation");
        assertFalse(table.getItems().isEmpty(), "Expected at least one inactive row (Monitor models ship with no S/N validation)");
        assertTrue(table.getItems().stream().noneMatch(SnValidationRow::isActive),
            "Only inactive rows should remain after filtering by 'No'");
    }

    @Test
    void selectingTypeNarrowsBrandMenuOptions() throws Exception {
        getSet("selSnTypes").add("Monitor");
        runOnFx(() -> invoke("refreshSnFilterMenus"));

        List<String> brandLabels = optionLabels(mnuBrand);
        assertEquals(List.of("Dell", "LG", "Samsung"), brandLabels,
            "Marca options should be scoped to brands used with the selected Tipo (Monitor)");
    }

    @Test
    void handleClearSnFiltersResetsSelectionsAndMenuLabels() throws Exception {
        getSet("selSnTypes").add("Notebook");
        getSet("selSnActive").add("No");
        runOnFx(() -> invoke("refreshSnFilterMenus"));

        runOnFx(() -> invoke("handleClearSnFilters"));

        assertTrue(getSet("selSnTypes").isEmpty());
        assertTrue(getSet("selSnBrands").isEmpty());
        assertTrue(getSet("selSnModels").isEmpty());
        assertTrue(getSet("selSnActive").isEmpty());
        assertEquals("Todas", mnuType.getText());
        assertEquals("Todas", mnuActive.getText());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private List<String> optionLabels(MenuButton btn) {
        return btn.getItems().stream()
            .filter(it -> it instanceof CustomMenuItem)
            .map(it -> ((CustomMenuItem) it).getContent())
            .filter(n -> n instanceof CheckBox chk && !"Todas".equals(chk.getText()))
            .map(n -> ((CheckBox) n).getText())
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Set<String> getSet(String name) throws Exception {
        return (Set<String>) getField(name);
    }

    private Object getField(String name) throws Exception {
        Field f = SettingsController.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(controller);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = SettingsController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private void invoke(String methodName) {
        try {
            Method m = SettingsController.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            m.invoke(controller);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void runOnFx(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "FX action did not complete in time");
    }
}
