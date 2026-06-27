package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class ProviderNoteController {

    @FXML private ComboBox<String> cmbProviderSearch;
    @FXML private TextField txtCuit;
    @FXML private ComboBox<String> cmbMotivo;

    @FXML private CheckBox chkEnableResponsible;
    @FXML private VBox gridResponsibleDetails;
    @FXML private TextField txtProviderResponsibleName;
    @FXML private TextField txtProviderResponsibleDni;

    public void initialize() {
        List<String> motivoOptions = ConfigService.getInstance().getConfig()
            .motivoOptions.getOrDefault("proveedor", List.of());
        cmbMotivo.setItems(FXCollections.observableArrayList(motivoOptions));

        chkEnableResponsible.selectedProperty().addListener((obs, was, now) ->
            gridResponsibleDetails.setDisable(!now));
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
    }
}
