package com.bunshock.note_app_for_it_frontend.controllers;

import com.bunshock.note_app_for_it_frontend.utils.core.ViewFactory;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

// Top-level "Envíos" section controller. Switching between its two child views (Remito de Envío /
// Historial de Envíos) is driven from MainController's sidebar flyout (see
// showRemitoTab()/showHistorialTab()); lblSectionTitle just names whichever view is showing.
// Mirrors PrestamosController's exact shape — same wrapper convention for a flyout group whose
// two leaf items are full, independent screens rather than tabs within a single form.
public class EnviosController {

    @FXML private Label lblSectionTitle;
    @FXML private StackPane dynamicContentArea;

    private ViewFactory viewFactory;

    public void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
        showHistorialView();
    }

    /** Called by MainController's "Remito de Envío" sidebar flyout item. */
    public void showRemitoTab() {
        lblSectionTitle.setText("REMITO DE ENVÍO");
        showRemitoView();
    }

    /** Called by MainController's "Historial de Envíos" sidebar flyout item. */
    public void showHistorialTab() {
        lblSectionTitle.setText("HISTORIAL DE ENVÍOS");
        showHistorialView();
    }

    private void showRemitoView() {
        if (viewFactory == null) return;
        dynamicContentArea.getChildren().setAll(viewFactory.getRemitoNoteView());
        // Same staleness fix as PrestamosController.showNewLoanView() — a stock-shortage warning
        // shown before navigating away must be recomputed on return, not left stuck from before
        // the shortage was actually fixed elsewhere (e.g. Base de Datos).
        viewFactory.getRemitoNoteController().refreshStockWarning();
    }

    private void showHistorialView() {
        if (viewFactory == null) return;
        dynamicContentArea.getChildren().setAll(viewFactory.getRemitoHistoryView());
        // ViewFactory caches this view for the session — without this, a Remito generated in the
        // same session wouldn't appear here until the app restarts (same staleness fix as
        // PrestamosController.showHistoryView()'s own refresh() call).
        viewFactory.getRemitoHistoryController().refresh();
    }
}
