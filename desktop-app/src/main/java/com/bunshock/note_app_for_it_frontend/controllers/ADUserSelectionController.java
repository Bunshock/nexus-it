package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.Comparator;
import java.util.List;

import org.controlsfx.control.PopOver;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;

import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public class ADUserSelectionController {

    @FXML private ListView<ADUser> lstResults;
    @FXML private Button btnCancel, btnSelect;

    private AdSearchHost parentController;
    private ADUser selectedUser;

    public void setParentController(AdSearchHost parent) {
        this.parentController = parent;
    }

    public void setResults(List<ADUser> results) {
        List<ADUser> sorted = results.stream()
            .sorted(Comparator.comparing(ADUser::getFullName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
        lstResults.setItems(FXCollections.observableArrayList(sorted));
        setupCellFactory();
    }

    // How long the mouse must stay over a row before the PopOver appears. Without this, a
    // virtualized ListView firing mouseEntered/mouseExited on every cell that slides under a
    // stationary cursor during a scrollbar drag triggers a full PopOver show()/hide() (a real
    // animated popup Stage) per row — that's what made scrolling feel laggy/stuttery.
    private static final Duration POPOVER_HOVER_DELAY = Duration.millis(400);

    private void setupCellFactory() {
        lstResults.setCellFactory(lv -> new ListCell<ADUser>() {
            private final PopOver popOver = new PopOver();
            private final PauseTransition hoverDelay = new PauseTransition(POPOVER_HOVER_DELAY);

            { hoverDelay.setOnFinished(e -> popOver.show(this)); }

            @Override
            protected void updateItem(ADUser user, boolean empty) {
                super.updateItem(user, empty);

                hoverDelay.stop();
                if (popOver.isShowing()) popOver.hide();

                if (empty || user == null) {
                    setText(null);
                    setGraphic(null);
                    setOnMouseEntered(null);
                    setOnMouseExited(null);
                } else {
                    setText(user.getFullName() + " [" + user.getUsername() + "]");
                    
                    VBox detailsBox = new VBox(8);
                    detailsBox.setStyle("-fx-padding: 15; -fx-background-color: #1e293b; -fx-border-color: #0c8570; -fx-border-width: 1;");
                    
                    Label lblHeader = new Label("DETALLES DE ACTIVE DIRECTORY");
                    lblHeader.setStyle("-fx-text-fill: #0c8570; -fx-font-weight: bold; -fx-font-size: 10px;");
                    
                    Label lblData = new Label(
                        "DNI: " + user.getDni() + "\n" +
                        "EMAIL: " + user.getEmail() + "\n" +
                        "OU: " + user.getDistinguishedName()
                    );
                    lblData.setStyle("-fx-text-fill: white; -fx-font-size: 11px;");
                    lblData.setWrapText(true);
                    lblData.setMaxWidth(300);

                    detailsBox.getChildren().addAll(lblHeader, lblData);

                    popOver.setContentNode(detailsBox);

                    popOver.setOpacity(0.95);
                    popOver.setArrowSize(10);
                    popOver.setArrowIndent(12);
                    popOver.setCornerRadius(8);

                    popOver.setArrowLocation(PopOver.ArrowLocation.LEFT_CENTER);
                    popOver.setAnimated(true);

                    this.setOnMouseEntered(event -> {
                        if (!popOver.isShowing()) {
                            hoverDelay.playFromStart();
                        }
                    });

                    this.setOnMouseExited(event -> {
                        hoverDelay.stop();
                        if (popOver.isShowing()) {
                            popOver.hide();
                        }
                    });
                }
            }
        });
    }

    @FXML
    private void handleSelection() {
        selectedUser = lstResults.getSelectionModel().getSelectedItem();
        if (selectedUser != null) {
            parentController.fillUserData(selectedUser);
            closeWindow();
        }
    }

    @FXML
    private void handleCancel() {
        ((Stage) btnCancel.getScene().getWindow()).close();
        parentController.onAdSelectionDialogClosed();
    }

    private void closeWindow() {
        ((Stage) btnSelect.getScene().getWindow()).close();
        parentController.onAdSelectionDialogClosed();
        parentController.highlightFields("#0c8570");
        parentController.triggerFeedback("✔ Usuario cargado", "#0c8570");
    }

    @FXML
    public void initialize() {
        lstResults.getSelectionModel().selectedItemProperty()
            .addListener((obs, old, sel) -> btnSelect.setDisable(sel == null));

        // Double-click shortcut for user selection
        lstResults.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && lstResults.getSelectionModel().getSelectedItem() != null) {
                handleSelection();
            }
        });
    }

}