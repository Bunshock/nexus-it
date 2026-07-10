package com.bunshock.note_app_for_it_frontend.utils;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.controllers.HistoryController;
import com.bunshock.note_app_for_it_frontend.controllers.NoteGeneratorController;
import com.bunshock.note_app_for_it_frontend.controllers.ProviderNoteController;
import com.bunshock.note_app_for_it_frontend.controllers.UserNoteController;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

public class ViewFactory {

    private static final String BASE = "/com/bunshock/note_app_for_it_frontend/views/";

    private VBox userNoteView;
    private VBox providerNoteView;
    private UserNoteController userNoteController;
    private ProviderNoteController providerNoteController;

    private Parent generatorView;
    private Parent historyView;
    private HistoryController historyController;
    private Parent databaseView;
    private Parent settingsView;
    private Parent aboutView;
    private Parent profileView;

    public VBox getUserNoteView() {
        if (userNoteView == null) {
            FXMLLoader loader = loader("UserNoteView.fxml");
            userNoteView = load(loader);
            userNoteController = loader.getController();
        }
        return userNoteView;
    }

    public VBox getProviderNoteView() {
        if (providerNoteView == null) {
            FXMLLoader loader = loader("ProviderNoteView.fxml");
            providerNoteView = load(loader);
            providerNoteController = loader.getController();
        }
        return providerNoteView;
    }

    public Parent getGeneratorView() {
        if (generatorView == null) {
            FXMLLoader loader = loader("NoteGeneratorView.fxml");
            generatorView = load(loader);
            NoteGeneratorController ctrl = loader.getController();
            ctrl.setViewFactory(this);
        }
        return generatorView;
    }

    public Parent getHistoryView() {
        if (historyView == null) {
            FXMLLoader loader = loader("HistoryView.fxml");
            historyView = load(loader);
            historyController = loader.getController();
        }
        return historyView;
    }

    public HistoryController getHistoryController() { return historyController; }

    public Parent getDatabaseView() {
        if (databaseView == null) databaseView = load(loader("DatabaseSectionView.fxml"));
        return databaseView;
    }

    public Parent getSettingsView() {
        if (settingsView == null) settingsView = load(loader("SettingsView.fxml"));
        return settingsView;
    }

    public Parent getAboutView() {
        if (aboutView == null) aboutView = load(loader("AboutView.fxml"));
        return aboutView;
    }

    public Parent getProfileView() {
        if (profileView == null) profileView = load(loader("ProfileView.fxml"));
        return profileView;
    }

    public UserNoteController getUserNoteController() { return userNoteController; }
    public ProviderNoteController getProviderNoteController() { return providerNoteController; }

    private FXMLLoader loader(String name) {
        return new FXMLLoader(getClass().getResource(BASE + name));
    }

    @SuppressWarnings("unchecked")
    private <T extends Parent> T load(FXMLLoader loader) {
        try {
            return loader.load();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load view: " + loader.getLocation(), e);
        }
    }
}
