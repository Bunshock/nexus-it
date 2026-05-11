package com.bunshock.note_app_for_it_frontend.utils;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.controllers.ProviderNoteController;
import com.bunshock.note_app_for_it_frontend.controllers.UserNoteController;

import javafx.fxml.FXMLLoader;
import javafx.scene.layout.VBox;

public class ViewFactory {

    private VBox userNoteView;
    private VBox providerNoteView;

    // Pointers to controllers for clearing fields
    private UserNoteController userNoteController;
    private ProviderNoteController providerNoteController;

    public VBox getUserNoteView() {
        if (userNoteView == null) {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/bunshock/note_app_for_it_frontend/views/UserNoteView.fxml"));
                userNoteView = loader.load();
                userNoteController = loader.getController();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return userNoteView;
    }

    public VBox getProviderNoteView() {
        if (providerNoteView == null) {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/bunshock/note_app_for_it_frontend/views/ProviderNoteView.fxml"));
                providerNoteView = loader.load();
                providerNoteController = loader.getController();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return providerNoteView;
    }

    public UserNoteController getUserNoteController() {
        return userNoteController;
    }

    public ProviderNoteController getProviderNoteController() {
        return providerNoteController;
    }

}
