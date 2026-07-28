package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.models.AdCredentialResult;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.IAuditService;
import com.bunshock.note_app_for_it_frontend.services.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.services.WindowsIdentityService;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * Standalone login screen shown before MainView (and its startup connectivity overlay) is
 * even constructed — see App.java. Validates AD credentials, then AD group membership (app
 * access gate), then resolves the full profile and USER_ROLE, populating
 * TechnicianSessionService exactly once other checks pass. An ADMIN-role login activates
 * AdminSession immediately (no separate password prompt — this login already proved identity).
 */
public class LoginController {

    private static final String REASON_INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    private static final String REASON_NOT_IN_ALLOWED_GROUP = "NOT_IN_ALLOWED_GROUP";
    private static final String REASON_PROFILE_LOOKUP_FAILED = "PROFILE_LOOKUP_FAILED";
    private static final String REASON_AD_UNREACHABLE = "AD_UNREACHABLE";

    @FXML private TextField txtUsername;
    @FXML private PasswordField pfPassword;
    @FXML private Label lblLoginStatus;
    @FXML private Button btnLogin;
    @FXML private Button btnExit;

    private Runnable onLoginSuccess;

    public void setOnLoginSuccess(Runnable onLoginSuccess) {
        this.onLoginSuccess = onLoginSuccess;
    }

    public void initialize() {
        // Convenience only — never trusted for authentication. The technician still has to
        // type their own password; this just saves them typing their username too.
        String sessionEmail = WindowsIdentityService.getInstance().getSessionEmail();
        if (sessionEmail != null) {
            txtUsername.setText(TechnicianSessionService.deriveUsernameFromEmail(sessionEmail));
        }
        Platform.runLater(() -> (txtUsername.getText().isEmpty() ? txtUsername : pfPassword).requestFocus());
    }

    @FXML
    private void handleExit() {
        Platform.exit();
    }

    @FXML
    private void handleLogin() {
        String username = txtUsername.getText().trim();
        String password = pfPassword.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Ingrese usuario y contraseña.");
            return;
        }

        setBusy(true);
        showStatus("Verificando credenciales...", "#64748b");

        Thread t = new Thread(() -> {
            try {
                AdCredentialResult credentials = ServiceLocator.getInstance().getAdService()
                    .validateCredentials(username, password);

                if (!credentials.isValid()) {
                    logLoginAttempt(username, false, REASON_INVALID_CREDENTIALS);
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("Usuario o contraseña incorrectos.");
                    });
                    return;
                }

                String allowedGroup = allowedGroupName();
                if (allowedGroup != null && !credentials.getGroups().contains(allowedGroup)) {
                    logLoginAttempt(username, false, REASON_NOT_IN_ALLOWED_GROUP);
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("No tiene permisos para usar esta aplicación.");
                    });
                    return;
                }

                List<ADUser> profile = ServiceLocator.getInstance().getAdService().search(null, null, username);
                if (profile.isEmpty()) {
                    logLoginAttempt(username, false, REASON_PROFILE_LOOKUP_FAILED);
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("No se pudo obtener el perfil desde Active Directory.");
                    });
                    return;
                }

                ADUser user = profile.get(0);
                String role = ServiceLocator.getInstance().getUserRoleService().getRole(user.getUsername());
                logLoginAttempt(username, true, null);

                Platform.runLater(() -> {
                    TechnicianSessionService.getInstance().loginResolved(user, role);
                    if (IUserRoleService.ROLE_ADMIN.equals(role) || IUserRoleService.ROLE_SUPERADMIN.equals(role)) {
                        AdminSession.getInstance().activatePermanently();
                    }
                    if (onLoginSuccess != null) onLoginSuccess.run();
                });
            } catch (Exception adUnreachable) {
                logLoginAttempt(username, false, REASON_AD_UNREACHABLE);
                Platform.runLater(() -> {
                    setBusy(false);
                    showError("No se pudo conectar con Active Directory.");
                });
            }
        }, "login-auth");
        t.setDaemon(true);
        t.start();
    }

    /** Best-effort — CachingAuditService/SqliteAuditService already fail open, but this call
     *  site is on a background thread before ServiceLocator may be fully settled, so it's
     *  wrapped defensively too; a logging failure must never affect the login outcome. */
    private void logLoginAttempt(String username, boolean success, String failureReason) {
        try {
            IAuditService audit = ServiceLocator.getInstance().getAuditService();
            if (audit != null) audit.logLogin(username, success, failureReason);
        } catch (Exception ignored) { }
    }

    private String allowedGroupName() {
        try {
            var adAccess = ConfigService.getInstance().getConfig().adAccess;
            if (adAccess == null || adAccess.allowedGroupName == null || adAccess.allowedGroupName.isBlank()) {
                return null; // not yet configured — group check skipped, see AppConfig.AdAccessConfig
            }
            return adAccess.allowedGroupName.trim();
        } catch (IllegalStateException notLoaded) {
            return null;
        }
    }

    private void setBusy(boolean busy) {
        btnLogin.setDisable(busy);
        btnExit.setDisable(busy);
        txtUsername.setDisable(busy);
        pfPassword.setDisable(busy);
    }

    private void showStatus(String message, String hexColor) {
        lblLoginStatus.setText(message);
        lblLoginStatus.setStyle("-fx-text-fill: " + hexColor + ";");
    }

    private void showError(String message) {
        showStatus(message, "#ef4444");
    }
}
