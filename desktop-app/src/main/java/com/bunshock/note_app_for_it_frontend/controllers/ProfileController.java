package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.util.Duration;

public class ProfileController {

    private static final Pattern NAME_PATTERN =
        Pattern.compile("^\\p{L}+( \\p{L}+)*$");
    private static final Pattern DNI_PATTERN =
        Pattern.compile("^\\d{7,8}$");
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @FXML private TextField txtProfileName;
    @FXML private TextField txtProfileUsername;
    @FXML private TextField txtProfileDni;
    @FXML private TextField txtProfileEmail;
    @FXML private Label lblProfileStatus;
    @FXML private Button btnSave;
    @FXML private Button btnRefreshFromAd;

    public void initialize() {
        txtProfileName.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            return newText.isEmpty() || newText.matches("[\\p{L} ]*") ? change : null;
        }));
        txtProfileDni.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            return newText.length() <= 8 && newText.matches("\\d*") ? change : null;
        }));

        TechnicianSessionService.getInstance().addOnChangeListener(this::populateFieldsFromSession);
        AdminSession.getInstance().addOnActivateListener(this::updateEditability);
        AdminSession.getInstance().addOnDeactivateListener(this::updateEditability);

        populateFieldsFromSession();
        updateEditability();
    }

    private void populateFieldsFromSession() {
        TechnicianSessionService session = TechnicianSessionService.getInstance();
        txtProfileName.setText(orEmpty(session.getName()));
        txtProfileUsername.setText(orEmpty(session.getUsername()));
        txtProfileDni.setText(orEmpty(session.getDni()));
        txtProfileEmail.setText(orEmpty(session.getEmail()));

        if (session.getLastError() != null) {
            flashStatus(session.getLastError(), "#f59e0b");
        } else if (session.isResolved()) {
            String message = session.getLastUpdateSource() == TechnicianSessionService.UpdateSource.MANUAL
                ? "Perfil guardado correctamente"
                : "Perfil actualizado desde Active Directory";
            flashStatus(message, "#0c8570");
        } else {
            lblProfileStatus.setText("");
        }
    }

    private void updateEditability() {
        boolean adminActive = AdminSession.getInstance().isActive();
        txtProfileName.setDisable(!adminActive);
        txtProfileUsername.setDisable(!adminActive);
        txtProfileDni.setDisable(!adminActive);
        txtProfileEmail.setDisable(!adminActive);
        btnSave.setVisible(adminActive);
        btnSave.setManaged(adminActive);
    }

    @FXML
    private void handleRefreshFromAd() {
        btnRefreshFromAd.setDisable(true);
        String originalText = btnRefreshFromAd.getText();
        btnRefreshFromAd.setText("Actualizando...");
        flashStatus("Consultando Active Directory...", "#64748b");

        Thread t = new Thread(() -> {
            TechnicianSessionService.getInstance().refreshFromWindowsSession();
            Platform.runLater(() -> {
                btnRefreshFromAd.setDisable(false);
                btnRefreshFromAd.setText(originalText);
            });
        }, "profile-ad-refresh");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void handleSave() {
        String name = txtProfileName.getText().trim();
        String username = txtProfileUsername.getText().trim();
        String dni = txtProfileDni.getText().trim();
        String email = txtProfileEmail.getText().trim();

        if (name.isEmpty()) {
            flashStatus("El nombre es obligatorio", "#ef4444");
            return;
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            flashStatus("El nombre solo puede contener letras y espacios simples", "#ef4444");
            return;
        }
        if (!dni.isEmpty() && !DNI_PATTERN.matcher(dni).matches()) {
            flashStatus("El DNI debe tener 7 u 8 dígitos, sin puntos", "#ef4444");
            return;
        }
        if (!email.isEmpty() && !EMAIL_PATTERN.matcher(email).matches()) {
            flashStatus("El email no es válido", "#ef4444");
            return;
        }

        // populateFieldsFromSession() (triggered by this call, via the change listener) shows
        // the "Perfil actualizado" confirmation — no separate status message needed here.
        TechnicianSessionService.getInstance().applyManualOverride(name, username, email, dni);
    }

    private void flashStatus(String message, String hexColor) {
        lblProfileStatus.setStyle("-fx-text-fill: " + hexColor + "; -fx-font-weight: bold;");
        lblProfileStatus.setText(message);
        lblProfileStatus.setOpacity(0.2);
        FadeTransition fade = new FadeTransition(Duration.millis(200), lblProfileStatus);
        fade.setFromValue(0.2);
        fade.setToValue(1.0);
        fade.play();
    }

    private String orEmpty(String s) { return s != null ? s : ""; }
}
