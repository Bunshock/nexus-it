package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

public class UserNoteController {

    @FXML private ToggleGroup userNoteTypeGroup;
    @FXML private ToggleButton btnTypeEntrega;
    @FXML private ToggleButton btnTypeDevolucion;
    @FXML private ToggleButton btnTypeFinContrato;
    @FXML private ToggleButton btnTypeRecambio;

    @FXML private VBox vboxMotivo;
    @FXML private ComboBox<String> cmbMotivo;

    @FXML private Label lblADStatus;
    @FXML private TextField txtUserDni;
    @FXML private TextField txtUserName;
    @FXML private TextField txtUserAccount;
    @FXML private Label lblUserEmail;

    public void initialize() {
        userNoteTypeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                oldToggle.setSelected(true);
                return;
            }
            updateMotivoVisibility((ToggleButton) newToggle);
        });

        loadMotivoOptions("entrega");
        updateMotivoVisibility(btnTypeEntrega);
        btnTypeEntrega.setSelected(true);
    }

    private void updateMotivoVisibility(ToggleButton selected) {
        boolean showMotivo = selected == btnTypeEntrega
                          || selected == btnTypeRecambio
                          || selected == btnTypeDevolucion
                          || selected == btnTypeFinContrato;
        vboxMotivo.setVisible(showMotivo);
        vboxMotivo.setManaged(showMotivo);

        if (showMotivo) {
            String key;
            if (selected == btnTypeRecambio) key = "recambio";
            else if (selected == btnTypeDevolucion) key = "devolucion";
            else key = "entrega";
            loadMotivoOptions(key);
        }
    }

    private void loadMotivoOptions(String key) {
        List<String> options = ConfigService.getInstance().getConfig().motivoOptions.getOrDefault(key, List.of());
        cmbMotivo.setItems(FXCollections.observableArrayList(options));
        cmbMotivo.getSelectionModel().clearSelection();
    }

    public String getSelectedNoteType() {
        if (userNoteTypeGroup.getSelectedToggle() == null) return "ENTREGA";
        return ((ToggleButton) userNoteTypeGroup.getSelectedToggle()).getText();
    }

    public String getMotivo() {
        return cmbMotivo.getValue();
    }

    public String getUserDni() { return txtUserDni.getText().trim(); }
    public String getUserName() { return txtUserName.getText().trim(); }
    public String getUserAccount() { return txtUserAccount.getText().trim(); }
    public String getUserEmail() {
        String raw = lblUserEmail.getText();
        return raw.startsWith("email: ") ? raw.substring(7).trim() : raw.trim();
    }

    @FXML
    private void handleADSearch() {
        String dni = txtUserDni.getText().trim();
        String name = txtUserName.getText().trim();
        String username = txtUserAccount.getText().trim();

        if (dni.isEmpty() && name.isEmpty() && username.isEmpty()) {
            highlightFields("#f59e0b");
            triggerFeedback("Ingrese criterios de búsqueda", "#f59e0b");
            return;
        }

        List<ADUser> results = ServiceLocator.getInstance().getAdService().search(dni, name, username);

        if (results.isEmpty()) {
            highlightFields("#ef4444");
            triggerFeedback("Usuario no encontrado", "#ef4444");
            return;
        }

        if (results.size() == 1) {
            fillUserData(results.get(0));
            highlightFields("#0c8570");
            triggerFeedback("Usuario cargado", "#0c8570");
        } else {
            showUserSelectionDialog(results);
        }
    }

    public void fillUserData(ADUser user) {
        txtUserDni.setText(user.getDni());
        txtUserName.setText(user.getFullName());
        txtUserAccount.setText(user.getUsername());
        lblUserEmail.setText("email: " + user.getEmail());
    }

    private void showUserSelectionDialog(List<ADUser> results) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/views/ADUserSelectionView.fxml"));
            Parent root = loader.load();
            ADUserSelectionController ctrl = loader.getController();
            ctrl.setParentController(this);
            ctrl.setResults(results);

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setTitle("Búsqueda AD - Seleccionar Usuario");
            stage.setScene(new Scene(root));
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void triggerFeedback(String message, String hexColor) {
        lblADStatus.setText(message);
        lblADStatus.setStyle("-fx-text-fill: " + hexColor + "; -fx-font-size: 10px; -fx-font-weight: bold;");
        lblADStatus.setOpacity(1.0);

        FadeTransition fade = new FadeTransition(Duration.millis(400), lblADStatus);
        fade.setDelay(Duration.millis(2000));
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            lblADStatus.setText("");
            lblADStatus.setStyle("");
            lblADStatus.setOpacity(1.0);
            resetFieldStyles();
        });
        fade.play();
    }

    private void highlightFields(String hexColor) {
        String style = "-fx-border-color: " + hexColor
            + "; -fx-border-width: 1.5; -fx-border-radius: 4; -fx-background-radius: 4;";
        txtUserDni.setStyle(style);
        txtUserName.setStyle(style);
        txtUserAccount.setStyle(style);
    }

    private void resetFieldStyles() {
        txtUserDni.setStyle("");
        txtUserName.setStyle("");
        txtUserAccount.setStyle("");
    }

    @FXML
    private void handleClearUserFields() {
        txtUserDni.clear();
        txtUserName.clear();
        txtUserAccount.clear();
        lblUserEmail.setText("email: ");
        cmbMotivo.getSelectionModel().clearSelection();
        resetFieldStyles();
        lblADStatus.setText("");
    }

    public void clearAllFields() {
        handleClearUserFields();
    }
}
