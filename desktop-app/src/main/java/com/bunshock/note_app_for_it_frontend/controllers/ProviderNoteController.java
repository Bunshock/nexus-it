package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;
import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.animation.FadeTransition;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public class ProviderNoteController {

    private static final Pattern NAME_PATTERN =
        Pattern.compile("^\\p{L}+( \\p{L}+)*$");
    private static final Pattern DNI_PATTERN =
        Pattern.compile("^\\d{7,8}$");

    // Matches UserNoteController's FEEDBACK_HOLD/FEEDBACK_FADE so every validation error shown
    // when Generar Nota is clicked fades away at the same speed, across all three note forms.
    private static final Duration ERROR_HOLD = Duration.millis(2000);
    private static final Duration ERROR_FADE = Duration.millis(650);

    @FXML private ComboBox<EquipmentProvider> cmbProviderSearch;
    @FXML private TextField txtCuit;
    @FXML private ComboBox<String> cmbMotivo;

    @FXML private CheckBox chkEnableResponsible;
    @FXML private VBox gridResponsibleDetails;
    @FXML private TextField txtProviderResponsibleName;
    @FXML private TextField txtProviderResponsibleDni;
    @FXML private Label lblResponsibleStatus;
    @FXML private Label lblProviderStatus;

    public void initialize() {
        List<String> motivoOptions = ConfigService.getInstance().getConfig()
            .motivoOptions.getOrDefault("proveedor", List.of());
        cmbMotivo.setItems(FXCollections.observableArrayList(motivoOptions));

        refreshProviders();

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

    /** Reloads the provider list from the catalog (Configuración → Base de Datos), keeping the
     * current selection if it still exists. Called on initialize() and again every time this
     * tab is shown (NoteGeneratorController.showProviderNoteView()) — ViewFactory caches this
     * view for the session, so without a re-fetch, a provider an admin adds mid-session
     * wouldn't appear until the app restarts (same staleness issue History had). */
    public void refreshProviders() {
        EquipmentProvider current = cmbProviderSearch.getValue();
        List<EquipmentProvider> providers = ServiceLocator.getInstance().getEquipmentService().getAllProviders();
        cmbProviderSearch.setItems(FXCollections.observableArrayList(providers));
        if (current != null) {
            providers.stream()
                .filter(p -> p.getId() == current.getId())
                .findFirst()
                .ifPresent(cmbProviderSearch::setValue);
        }
    }

    public boolean validateAndShowErrors() {
        if (cmbProviderSearch.getValue() == null) {
            showProviderError("Debe seleccionar un proveedor");
            return false;
        }
        lblProviderStatus.setText("");

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

    private void showProviderError(String message) {
        fadeOutError(lblProviderStatus, message);
    }

    private void showResponsibleError(String message) {
        fadeOutError(lblResponsibleStatus, message);
    }

    private void fadeOutError(Label label, String message) {
        label.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
        label.setText(message);
        label.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(ERROR_FADE, label);
        fade.setDelay(ERROR_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            label.setText("");
            label.setStyle("");
            label.setOpacity(1.0);
        });
        fade.play();
    }

    public String getProviderName() {
        EquipmentProvider selected = cmbProviderSearch.getValue();
        return selected != null ? selected.getName() : "";
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
        lblProviderStatus.setText("");
    }
}
