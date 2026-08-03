package com.bunshock.note_app_for_it_frontend.controllers;

import com.bunshock.note_app_for_it_frontend.utils.ViewFactory;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

// Top-level "Préstamos" section controller. Switching between its two child views (Historial de
// Préstamos / Cargar Préstamo Interno) is driven from MainController's sidebar flyout (see
// showHistoryTab()/showNewLoanTab()); lblSectionTitle just names whichever view is showing.
public class PrestamosController {

    @FXML private Label lblSectionTitle;
    @FXML private StackPane dynamicContentArea;

    private ViewFactory viewFactory;

    public void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
        showHistoryView();
    }

    /** Called by MainController's "Historial de Préstamos" sidebar flyout item. */
    public void showHistoryTab() {
        lblSectionTitle.setText("HISTORIAL DE PRÉSTAMOS");
        showHistoryView();
    }

    /** Called by MainController's "Cargar Préstamo Interno" sidebar flyout item. */
    public void showNewLoanTab() {
        lblSectionTitle.setText("CARGAR PRÉSTAMO INTERNO");
        showNewLoanView();
    }

    private void showNewLoanView() {
        if (viewFactory != null) dynamicContentArea.getChildren().setAll(viewFactory.getPrestamoNewLoanView());
    }

    private void showHistoryView() {
        if (viewFactory == null) return;
        dynamicContentArea.getChildren().setAll(viewFactory.getPrestamoHistoryView());
        // ViewFactory caches this view for the session — without this, a préstamo saved in the
        // same session wouldn't appear here until the app restarts (same staleness fix as
        // MainController.handleShowHistory()'s HistoryController.refresh() call).
        viewFactory.getPrestamoHistoryController().refresh();
    }
}
