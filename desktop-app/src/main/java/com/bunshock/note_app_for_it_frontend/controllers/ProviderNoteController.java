package com.bunshock.note_app_for_it_frontend.controllers;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;

public class ProviderNoteController {

    @FXML
    private TextField txtProviderName;

    public void initialize() {
        // To be implemented ...
    }

    public void clearAllFields() {
        txtProviderName.clear();
    }

}