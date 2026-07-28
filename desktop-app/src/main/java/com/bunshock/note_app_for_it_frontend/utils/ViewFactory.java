package com.bunshock.note_app_for_it_frontend.utils;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.controllers.AuditController;
import com.bunshock.note_app_for_it_frontend.controllers.HistoryController;
import com.bunshock.note_app_for_it_frontend.controllers.NoteGeneratorController;
import com.bunshock.note_app_for_it_frontend.controllers.PrestamoHistoryController;
import com.bunshock.note_app_for_it_frontend.controllers.PrestamosController;
import com.bunshock.note_app_for_it_frontend.controllers.ProviderNoteController;
import com.bunshock.note_app_for_it_frontend.controllers.RemitoNoteController;
import com.bunshock.note_app_for_it_frontend.controllers.SettingsController;
import com.bunshock.note_app_for_it_frontend.controllers.UserNoteController;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

public class ViewFactory {

    private static final String BASE = "/com/bunshock/note_app_for_it_frontend/views/";

    private VBox userNoteView;
    private VBox providerNoteView;
    private VBox remitoNoteView;
    private UserNoteController userNoteController;
    private ProviderNoteController providerNoteController;
    private RemitoNoteController remitoNoteController;

    private Parent generatorView;
    private Parent historyView;
    private HistoryController historyController;
    private Parent databaseView;
    private Parent settingsView;
    private SettingsController settingsController;
    private Parent aboutView;
    private Parent profileView;
    private Parent auditView;
    private AuditController auditController;

    private Parent prestamosView;
    private PrestamosController prestamosController;
    private Parent prestamoNewLoanView;
    private Parent prestamoHistoryView;
    private PrestamoHistoryController prestamoHistoryController;

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

    public VBox getRemitoNoteView() {
        if (remitoNoteView == null) {
            FXMLLoader loader = loader("RemitoNoteView.fxml");
            remitoNoteView = load(loader);
            remitoNoteController = loader.getController();
        }
        return remitoNoteView;
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
        if (settingsView == null) {
            FXMLLoader loader = loader("SettingsView.fxml");
            settingsView = load(loader);
            settingsController = loader.getController();
        }
        return settingsView;
    }

    public SettingsController getSettingsController() { return settingsController; }

    public Parent getAboutView() {
        if (aboutView == null) aboutView = load(loader("AboutView.fxml"));
        return aboutView;
    }

    public Parent getProfileView() {
        if (profileView == null) profileView = load(loader("ProfileView.fxml"));
        return profileView;
    }

    public Parent getAuditView() {
        if (auditView == null) {
            FXMLLoader loader = loader("AuditView.fxml");
            auditView = load(loader);
            auditController = loader.getController();
        }
        return auditView;
    }

    public AuditController getAuditController() { return auditController; }

    public UserNoteController getUserNoteController() { return userNoteController; }
    public ProviderNoteController getProviderNoteController() { return providerNoteController; }
    public RemitoNoteController getRemitoNoteController() { return remitoNoteController; }

    public Parent getPrestamosView() {
        if (prestamosView == null) {
            FXMLLoader loader = loader("PrestamosView.fxml");
            prestamosView = load(loader);
            prestamosController = loader.getController();
            prestamosController.setViewFactory(this);
        }
        return prestamosView;
    }

    public PrestamosController getPrestamosController() { return prestamosController; }

    public Parent getPrestamoNewLoanView() {
        if (prestamoNewLoanView == null) prestamoNewLoanView = load(loader("PrestamoNewLoanView.fxml"));
        return prestamoNewLoanView;
    }

    public Parent getPrestamoHistoryView() {
        if (prestamoHistoryView == null) {
            FXMLLoader loader = loader("PrestamoHistoryView.fxml");
            prestamoHistoryView = load(loader);
            prestamoHistoryController = loader.getController();
        }
        return prestamoHistoryView;
    }

    public PrestamoHistoryController getPrestamoHistoryController() { return prestamoHistoryController; }

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
