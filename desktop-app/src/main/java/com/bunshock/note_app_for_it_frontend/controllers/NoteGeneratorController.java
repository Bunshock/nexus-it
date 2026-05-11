package com.bunshock.note_app_for_it_frontend.controllers;

import com.bunshock.note_app_for_it_frontend.utils.ViewFactory;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;

public class NoteGeneratorController {

    // Factory to load views for different profiles
    private ViewFactory viewFactory;

    // Profile selection buttons
    @FXML private ToggleButton btnUserNote, btnProviderNote;
    @FXML private ToggleGroup entityGroup;
    
    // Specific note profile content area
    @FXML private StackPane dynamicContentArea;

    // Observations field common to all profiles
    @FXML private TextField txtObservations;

    public void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
        // Default profile selection: user note
        showUserNoteView();
    }

    public void initialize() {
        btnUserNote.setOnAction(e -> showUserNoteView());
        btnProviderNote.setOnAction(e -> showProviderNoteView());

        // Ensure one profile is always selected
        entityGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                oldToggle.setSelected(true);
            }
        });
    }

    private void showUserNoteView() {
        dynamicContentArea.getChildren().setAll(viewFactory.getUserNoteView());
    }

    private void showProviderNoteView() {
        dynamicContentArea.getChildren().setAll(viewFactory.getProviderNoteView());
    }

    @FXML
    private void handleClearForm() {
        txtObservations.clear();

        // Clear fields in the currently loaded profile view
        if (viewFactory.getUserNoteView() != null) {
            viewFactory.getUserNoteController().clearAllFields();
        }

        if (viewFactory.getProviderNoteView() != null) {
            viewFactory.getProviderNoteController().clearAllFields();
        }
    }

    @FXML
    private void handleGenerateNote() {
        System.out.println("Generando nota ...");
    }

}