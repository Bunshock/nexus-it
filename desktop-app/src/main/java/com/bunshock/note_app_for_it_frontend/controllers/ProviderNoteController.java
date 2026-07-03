package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;
import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.VBox;

public class ProviderNoteController {

    private static final Pattern NAME_PATTERN =
        Pattern.compile("^\\p{L}+( \\p{L}+)*$");
    private static final Pattern DNI_PATTERN =
        Pattern.compile("^\\d{7,8}$");

    @FXML private ComboBox<String> cmbProviderSearch;
    @FXML private TextField txtCuit;
    @FXML private ComboBox<String> cmbMotivo;

    @FXML private CheckBox chkEnableResponsible;
    @FXML private VBox gridResponsibleDetails;
    @FXML private TextField txtProviderResponsibleName;
    @FXML private TextField txtProviderResponsibleDni;
    @FXML private Label lblResponsibleStatus;

    public void initialize() {
        List<String> motivoOptions = ConfigService.getInstance().getConfig()
            .motivoOptions.getOrDefault("proveedor", List.of());
        cmbMotivo.setItems(FXCollections.observableArrayList(motivoOptions));

        chkEnableResponsible.selectedProperty().addListener((obs, was, now) ->
            gridResponsibleDetails.setDisable(!now));

        txtProviderResponsibleName.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            return newText.isEmpty() || newText.matches("[\\p{L} ]*") ? change : null;
        }));
        txtProviderResponsibleDni.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            return newText.length() <= 8 && newText.matches("\\d*") ? change : null;
        }));
    }

    public boolean validateAndShowErrors() {
        String name = txtProviderResponsibleName.getText().trim();
        String dni = txtProviderResponsibleDni.getText().trim();

        if (!name.isEmpty() && !NAME_PATTERN.matcher(name).matches()) {
            showResponsibleError("El nombre solo puede contener letras y espacios simples");
            return false;
        }
        if (!dni.isEmpty() && !DNI_PATTERN.matcher(dni).matches()) {
            showResponsibleError("El DNI debe tener 7 u 8 dígitos, sin puntos");
            return false;
        }
        lblResponsibleStatus.setText("");
        return true;
    }

    private void showResponsibleError(String message) {
        lblResponsibleStatus.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
        lblResponsibleStatus.setText(message);
    }

    public String getProviderName() {
        return cmbProviderSearch.getValue() != null
            ? cmbProviderSearch.getValue().trim() : "";
    }

    public String getCuit() { return txtCuit.getText().trim(); }

    public String getMotivo() { return cmbMotivo.getValue(); }

    public String getResponsibleName() {
        return chkEnableResponsible.isSelected()
            ? txtProviderResponsibleName.getText().trim() : "";
    }

    public String getResponsibleDni() {
        return chkEnableResponsible.isSelected()
            ? txtProviderResponsibleDni.getText().trim() : "";
    }

    public void clearAllFields() {
        cmbProviderSearch.getSelectionModel().clearSelection();
        cmbProviderSearch.setValue(null);
        txtCuit.clear();
        cmbMotivo.getSelectionModel().clearSelection();
        chkEnableResponsible.setSelected(false);
        txtProviderResponsibleName.clear();
        txtProviderResponsibleDni.clear();
        lblResponsibleStatus.setText("");
    }
}
