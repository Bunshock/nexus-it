package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import javafx.util.Duration;

public class ADUserSelectionController {

    @FXML private ListView<ADUser> lstResults;
    @FXML private Button btnCancel, btnSelect;

    private UserNoteController parentController;
    private ADUser selectedUser;

    public void setParentController(UserNoteController parent) {
        this.parentController = parent;
    }

    public void setResults(List<ADUser> results) {
        lstResults.setItems(FXCollections.observableArrayList(results));
        setupCellFactory();
    }

    private void setupCellFactory() {
        lstResults.setCellFactory(lv -> new ListCell<ADUser>() {
            @Override
            protected void updateItem(ADUser user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(user.getFullName() + " [" + user.getUsername() + "]");
                    
                    // The Hover Detail Tooltip
                    Tooltip details = new Tooltip(
                        "DNI: " + user.getDni() + "\n" +
                        "Email: " + user.getEmail() + "\n" +
                        "OU: " + user.getDistinguishedName() + "\n" +
                        "Grupos: " + user.getMemberOfSummary()
                    );
                    details.setShowDelay(Duration.millis(200));
                    details.setStyle("-fx-font-size: 11px; -fx-background-color: #334155;");
                    setTooltip(details);
                }
            }
        });
    }

    @FXML
    private void handleSelection() {
        selectedUser = lstResults.getSelectionModel().getSelectedItem();
        if (selectedUser != null) {
            parentController.updateUserData(
                selectedUser.getDni(),
                selectedUser.getFullName(),
                selectedUser.getUsername(),
                selectedUser.getEmail()
            );
            closeWindow();
        }
    }

    @FXML
    private void handleCancel() {
        ((Stage) btnCancel.getScene().getWindow()).close();
    }

    private void closeWindow() {
        ((Stage) btnSelect.getScene().getWindow()).close();
        parentController.animateSuccess();
    }
}