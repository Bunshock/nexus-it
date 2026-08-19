package com.bunshock.note_app_for_it_frontend.utils.core;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.controllers.admin.DatabaseSectionController;
import com.bunshock.note_app_for_it_frontend.controllers.envio.EnviosController;
import com.bunshock.note_app_for_it_frontend.controllers.history.HistoryController;
import com.bunshock.note_app_for_it_frontend.controllers.note.NoteGeneratorController;
import com.bunshock.note_app_for_it_frontend.controllers.prestamo.PrestamoHistoryController;
import com.bunshock.note_app_for_it_frontend.controllers.prestamo.PrestamoNewLoanController;
import com.bunshock.note_app_for_it_frontend.controllers.prestamo.PrestamosController;
import com.bunshock.note_app_for_it_frontend.controllers.note.ProviderNoteController;
import com.bunshock.note_app_for_it_frontend.controllers.envio.RemitoHistoryController;
import com.bunshock.note_app_for_it_frontend.controllers.envio.RemitoNoteController;
import com.bunshock.note_app_for_it_frontend.controllers.admin.SettingsController;
import com.bunshock.note_app_for_it_frontend.controllers.note.UserNoteController;

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
    private NoteGeneratorController generatorController;
    private Parent remitoNoteView;
    private RemitoNoteController remitoNoteController;
    private Parent remitoHistoryView;
    private RemitoHistoryController remitoHistoryController;
    private Parent enviosView;
    private EnviosController enviosController;
    private Parent historyView;
    private HistoryController historyController;
    private Parent databaseView;
    private DatabaseSectionController databaseController;
    private Parent settingsView;
    private SettingsController settingsController;
    private Parent aboutView;
    private Parent profileView;

    private Parent prestamosView;
    private PrestamosController prestamosController;
    private Parent prestamoNewLoanView;
    private PrestamoNewLoanController prestamoNewLoanController;
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

    public Parent getGeneratorView() {
        if (generatorView == null) {
            FXMLLoader loader = loader("NoteGeneratorView.fxml");
            generatorView = load(loader);
            generatorController = loader.getController();
            generatorController.setViewFactory(this);
        }
        return generatorView;
    }

    public NoteGeneratorController getGeneratorController() { return generatorController; }

    public Parent getRemitoNoteView() {
        if (remitoNoteView == null) {
            FXMLLoader loader = loader("RemitoNoteView.fxml");
            remitoNoteView = load(loader);
            remitoNoteController = loader.getController();
        }
        return remitoNoteView;
    }

    public RemitoNoteController getRemitoNoteController() { return remitoNoteController; }

    public Parent getRemitoHistoryView() {
        if (remitoHistoryView == null) {
            FXMLLoader loader = loader("RemitoHistoryView.fxml");
            remitoHistoryView = load(loader);
            remitoHistoryController = loader.getController();
        }
        return remitoHistoryView;
    }

    public RemitoHistoryController getRemitoHistoryController() { return remitoHistoryController; }

    public Parent getEnviosView() {
        if (enviosView == null) {
            FXMLLoader loader = loader("EnviosView.fxml");
            enviosView = load(loader);
            enviosController = loader.getController();
            enviosController.setViewFactory(this);
        }
        return enviosView;
    }

    public EnviosController getEnviosController() { return enviosController; }

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
        if (databaseView == null) {
            FXMLLoader loader = loader("DatabaseSectionView.fxml");
            databaseView = load(loader);
            databaseController = loader.getController();
        }
        return databaseView;
    }

    public DatabaseSectionController getDatabaseSectionController() { return databaseController; }

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

    public UserNoteController getUserNoteController() { return userNoteController; }
    public ProviderNoteController getProviderNoteController() { return providerNoteController; }

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
        if (prestamoNewLoanView == null) {
            FXMLLoader loader = loader("PrestamoNewLoanView.fxml");
            prestamoNewLoanView = load(loader);
            prestamoNewLoanController = loader.getController();
        }
        return prestamoNewLoanView;
    }

    public PrestamoNewLoanController getPrestamoNewLoanController() { return prestamoNewLoanController; }

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
