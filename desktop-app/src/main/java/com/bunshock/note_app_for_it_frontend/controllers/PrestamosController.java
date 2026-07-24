package com.bunshock.note_app_for_it_frontend.controllers;

import com.bunshock.note_app_for_it_frontend.utils.ViewFactory;

import javafx.fxml.FXML;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;

// Top-level "Préstamos" section controller — mirrors NoteGeneratorController's own
// btnUserNote/btnProviderNote + dynamicContentArea toggle shape, one level up. See
// CLAUDE.md's "Préstamos section" for the two child views this switches between.
public class PrestamosController {

    @FXML private ToggleButton btnNewLoan;
    @FXML private ToggleButton btnHistory;
    @FXML private ToggleGroup typeGroup;
    @FXML private StackPane dynamicContentArea;

    private ViewFactory viewFactory;

    public void initialize() {
        btnNewLoan.setOnAction(e -> showNewLoanView());
        btnHistory.setOnAction(e -> showHistoryView());

        typeGroup.selectedToggleProperty().addListener((obs, old, next) -> {
            if (next == null) old.setSelected(true);
        });
    }

    public void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
        if (btnHistory.isSelected()) {
            showHistoryView();
        } else {
            showNewLoanView();
        }
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

    /** Called by MainController every time the Préstamos section is navigated to. */
    public void refreshHistory() {
        if (viewFactory != null && viewFactory.getPrestamoHistoryController() != null) {
            viewFactory.getPrestamoHistoryController().refresh();
        }
    }
}
