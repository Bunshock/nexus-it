package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;

import com.bunshock.note_app_for_it_frontend.controllers.prestamo.PrestamosController;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// showHistoryTab()/showNewLoanTab() are what MainController's sidebar flyout (see
// MainView.fxml's flyoutPrestamos, "Historial de Préstamos"/"Cargar Préstamo Interno") calls to
// switch views — the only way to switch now that the in-page toggle bar was removed in favor of
// a plain page-title-card label. viewFactory is left null here (already tolerated by this
// controller's own null checks), so only the title-label outcome is asserted, not actual
// view-switching — same "impractical to exercise the rest of this controller in isolation" gap
// already accepted elsewhere in this suite for controllers reaching ViewFactory/ServiceLocator.
class PrestamosControllerTest {

    @BeforeAll
    static void initFxToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
    }

    private PrestamosController newController() throws Exception {
        PrestamosController c = new PrestamosController();
        setField(c, "lblSectionTitle", new Label());
        setField(c, "dynamicContentArea", new StackPane());
        return c;
    }

    private void setField(PrestamosController c, String name, Object value) throws Exception {
        Field f = PrestamosController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(c, value);
    }

    private String titleText(PrestamosController c) throws Exception {
        Field f = PrestamosController.class.getDeclaredField("lblSectionTitle");
        f.setAccessible(true);
        return ((Label) f.get(c)).getText();
    }

    @Test
    void showHistoryTabSetsTheHistoryTitle() throws Exception {
        PrestamosController c = newController();
        c.showHistoryTab();
        assertEquals("HISTORIAL DE PRÉSTAMOS", titleText(c));
    }

    @Test
    void showNewLoanTabSetsTheNewLoanTitle() throws Exception {
        PrestamosController c = newController();
        c.showNewLoanTab();
        assertEquals("CARGAR PRÉSTAMO INTERNO", titleText(c));
    }
}
