package com.bunshock.note_app_for_it_frontend.controllers;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

public class ProviderNoteController {

    // Provider name
    @FXML private ComboBox<String> cmbProviderSearch;

    // Responsible person info
    @FXML private CheckBox chkEnableResponsible;
    @FXML private GridPane gridResponsibleDetails;
    @FXML private TextField txtProviderResponsibleName;
    @FXML private TextField txtProviderResponsibleDni;

    public void initialize() {
        // Mock provider list
        ObservableList<String> mockProviders = FXCollections.observableArrayList(
            "Trendit",
            "Procom IT Solutions S.A.",
            "Veneta",
            "Personal",
            "Lenovo"
        );

        // Set provider combobox items
        cmbProviderSearch.setItems(mockProviders.sorted());

        // Enable/disable responsible details based on checkbox
        chkEnableResponsible.selectedProperty().addListener((obs, wasSelected, isNowSelected) -> {
            gridResponsibleDetails.setDisable(!isNowSelected);
        });
        }

    public void clearAllFields() {
        // Clear combobox
        cmbProviderSearch.getSelectionModel().clearSelection();
        cmbProviderSearch.setValue(null);
        
        // Clear responsible details
        txtProviderResponsibleName.clear();
        txtProviderResponsibleDni.clear();
    }

}