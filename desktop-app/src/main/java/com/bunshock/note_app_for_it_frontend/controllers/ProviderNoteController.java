package com.bunshock.note_app_for_it_frontend.controllers;

import org.controlsfx.control.SearchableComboBox;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

public class ProviderNoteController {

    @FXML
    private TextField txtProviderName;
    @FXML
    private SearchableComboBox<String> cmbItemType;
    @FXML
    private TextField txtSerialNumber;
    @FXML
    private TextArea txtObservations;

    public void initialize() {
        cmbItemType.setItems(FXCollections.observableArrayList("Notebook", "Monitor", "..."));
    }

    public void clearAllFields() {
        // To be implemented ...
    }

}