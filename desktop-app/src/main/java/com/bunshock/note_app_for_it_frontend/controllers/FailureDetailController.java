package com.bunshock.note_app_for_it_frontend.controllers;

import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.stage.Stage;

public class FailureDetailController {

    private static final int FAILURE_DETAILS_MAX_LENGTH = 200;

    @FXML private ComboBox<String> cmbFailureCause;
    @FXML private TextArea txtFailureDetails;
    @FXML private Label lblStatus;
    @FXML private Button btnSave;
    @FXML private Button btnCancel;

    private UserNoteController parentController;

    public void initialize() {
        cmbFailureCause.setItems(FXCollections.observableArrayList(
            ConfigService.getInstance().getConfig().fallaOptions));
        txtFailureDetails.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= FAILURE_DETAILS_MAX_LENGTH ? change : null));
    }

    public void setParentController(UserNoteController parent) {
        this.parentController = parent;
    }

    public void prefill(String cause, String details) {
        if (cause != null) cmbFailureCause.setValue(cause);
        if (details != null) txtFailureDetails.setText(details);
    }

    @FXML
    private void handleSave() {
        String cause = cmbFailureCause.getValue();
        String details = txtFailureDetails.getText().trim();

        if (cause == null || cause.isBlank()) {
            lblStatus.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
            lblStatus.setText("Debe seleccionar una causa");
            return;
        }

        parentController.setFailureDetails(cause, details);
        closeDialog();
    }

    @FXML
    private void handleCancel() {
        parentController.onFailureDialogCancelled();
        closeDialog();
    }

    private void closeDialog() {
        ((Stage) btnCancel.getScene().getWindow()).close();
    }
}
