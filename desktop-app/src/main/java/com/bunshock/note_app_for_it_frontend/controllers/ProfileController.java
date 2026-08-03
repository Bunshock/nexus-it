package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.SequentialTransition;
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
    private static final String ADMIN_HINT_SUFFIX =
        " Contacte a un administrador si estos datos no son correctos en Active Directory.";

    @FXML private TextField txtProfileName;
    @FXML private TextField txtProfileUsername;
    @FXML private TextField txtProfileDni;
    @FXML private TextField txtProfileEmail;
    @FXML private Label lblProfileStatus;
    @FXML private Button btnRefreshFromAd;

    private static final int DISPLAY_NAME_MAX_LENGTH = 20;
    // Matches NOTE_REPORT.technician_name's NVARCHAR(255) bound on SQL Server —
    // this field only restricted character set before, with no length limit.
    private static final int PROFILE_NAME_MAX_LENGTH = 255;

    @FXML private TextField txtDisplayName;
    @FXML private Label lblDisplayNameStatus;
    @FXML private Button btnSaveDisplayName;
    @FXML private Button btnResetDisplayName;

    public void initialize() {
        txtProfileName.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.length() > PROFILE_NAME_MAX_LENGTH) return null;
            return newText.isEmpty() || newText.matches("[\\p{L} ]*") ? change : null;
        }));
        txtProfileDni.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            return newText.length() <= 8 && newText.matches("\\d*") ? change : null;
        }));
        txtDisplayName.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.length() > DISPLAY_NAME_MAX_LENGTH) return null;
            return newText.isEmpty() || newText.matches("[\\p{L} ]*") ? change : null;
        }));

        TechnicianSessionService.getInstance().addOnChangeListener(this::populateFieldsFromSession);
        TechnicianSessionService.getInstance().addOnDisplayNameChangeListener(this::populateDisplayNameField);

        populateFieldsFromSession();
    }

    private void populateFieldsFromSession() {
        TechnicianSessionService session = TechnicianSessionService.getInstance();
        txtProfileName.setText(orEmpty(session.getName()));
        txtProfileUsername.setText(orEmpty(session.getUsername()));
        txtProfileDni.setText(orEmpty(session.getDni()));
        txtProfileEmail.setText(orEmpty(session.getEmail()));
        txtDisplayName.setText(orEmpty(session.getDisplayName()));

        if (session.getLastError() != null) {
            flashStatus(lblProfileStatus, session.getLastError(), "#f59e0b");
        } else if (session.isResolved()) {
            String message = session.getLastUpdateSource() == TechnicianSessionService.UpdateSource.MANUAL
                ? "Perfil guardado correctamente"
                : "Perfil actualizado desde Active Directory";
            flashStatus(lblProfileStatus, message, "#0c8570");
        } else {
            lblProfileStatus.setText("");
        }
    }

    // Separate from populateFieldsFromSession() (and its own listener, see initialize()) so that
    // saving/resetting "Nombre para mostrar" doesn't also re-flash lblProfileStatus with the AD
    // identity confirmation message — both used to route through the same onChangeListeners
    // notification, which fired every time either changed.
    private void populateDisplayNameField() {
        txtDisplayName.setText(orEmpty(TechnicianSessionService.getInstance().getDisplayName()));
    }

    // Refreshes name/dni/email from a fresh AD lookup by the already-known username — not a
    // re-authentication (the technician already logged in with a password to start this
    // session; see TechnicianSessionService.refreshProfileFromAd()). A failure here leaves the
    // existing session untouched and just reports the error, rather than wiping identity.
    @FXML
    private void handleRefreshFromAd() {
        String username = TechnicianSessionService.getInstance().getUsername();
        if (username == null) return;

        btnRefreshFromAd.setDisable(true);
        String originalText = btnRefreshFromAd.getText();
        btnRefreshFromAd.setText("Actualizando...");
        flashStatus(lblProfileStatus, "Consultando Active Directory...", "#64748b");

        Thread t = new Thread(() -> {
            TechnicianSessionService session = TechnicianSessionService.getInstance();
            try {
                var results = ServiceLocator.getInstance().getAdService().search(null, null, username);
                if (results.isEmpty()) {
                    session.reportProfileRefreshError(
                        "Usuario no encontrado en Active Directory." + ADMIN_HINT_SUFFIX);
                } else {
                    session.refreshProfileFromAd(results.get(0));
                }
            } catch (Exception adUnreachable) {
                session.reportProfileRefreshError(
                    "No se pudo conectar con Active Directory." + ADMIN_HINT_SUFFIX);
            }
            Platform.runLater(() -> {
                btnRefreshFromAd.setDisable(false);
                btnRefreshFromAd.setText(originalText);
            });
        }, "profile-ad-refresh");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void handleSaveDisplayName() {
        String value = txtDisplayName.getText().trim();
        if (!value.isEmpty() && !NAME_PATTERN.matcher(value).matches()) {
            flashStatus(lblDisplayNameStatus, "Solo se permiten letras y espacios simples", "#ef4444");
            return;
        }
        TechnicianSessionService.getInstance().setDisplayNamePreference(value);
        flashStatus(lblDisplayNameStatus, "Nombre para mostrar actualizado", "#0c8570");
    }

    @FXML
    private void handleResetDisplayName() {
        txtDisplayName.clear();
        handleSaveDisplayName();
    }

    private static final Duration STATUS_FADE_IN  = Duration.millis(200);
    private static final Duration STATUS_HOLD     = Duration.millis(2000);
    private static final Duration STATUS_FADE_OUT = Duration.millis(650);

    // Keeps the existing fade-in pulse (responsiveness feedback on every status change,
    // including a same-message repeat) and adds a hold + fade-out afterward, matching
    // UserNoteController.triggerFeedback / DatabaseSectionController.triggerFieldError's timing
    // so a save confirmation doesn't just sit on screen forever like it used to.
    private void flashStatus(Label label, String message, String hexColor) {
        if (label.getUserData() instanceof Animation previous) previous.stop();

        label.setStyle("-fx-text-fill: " + hexColor + "; -fx-font-weight: bold;");
        label.setText(message);
        label.setOpacity(0.2);

        FadeTransition fadeIn = new FadeTransition(STATUS_FADE_IN, label);
        fadeIn.setFromValue(0.2);
        fadeIn.setToValue(1.0);

        FadeTransition fadeOut = new FadeTransition(STATUS_FADE_OUT, label);
        fadeOut.setDelay(STATUS_HOLD);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> {
            label.setText("");
            label.setStyle("");
            label.setOpacity(1.0);
            label.setUserData(null);
        });

        SequentialTransition seq = new SequentialTransition(fadeIn, fadeOut);
        label.setUserData(seq);
        seq.play();
    }

    private String orEmpty(String s) { return s != null ? s : ""; }
}
