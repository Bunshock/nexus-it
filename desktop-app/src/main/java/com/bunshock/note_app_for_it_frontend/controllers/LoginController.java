package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.models.AdCredentialResult;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
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
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("Usuario o contraseña incorrectos.");
                    });
                    return;
                }

                String allowedGroup = allowedGroupName();
                if (allowedGroup != null && !credentials.getGroups().contains(allowedGroup)) {
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("No tiene permisos para usar esta aplicación.");
                    });
                    return;
                }

                List<ADUser> profile = ServiceLocator.getInstance().getAdService().search(null, null, username);
                if (profile.isEmpty()) {
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("No se pudo obtener el perfil desde Active Directory.");
                    });
                    return;
                }

                ADUser user = profile.get(0);
                String role = ServiceLocator.getInstance().getUserRoleService().getRole(user.getUsername());

                Platform.runLater(() -> {
                    TechnicianSessionService.getInstance().loginResolved(user, role);
                    if (IUserRoleService.ROLE_ADMIN.equals(role)) {
                        AdminSession.getInstance().activatePermanently();
                    }
                    if (onLoginSuccess != null) onLoginSuccess.run();
                });
            } catch (Exception adUnreachable) {
                Platform.runLater(() -> {
                    setBusy(false);
                    showError("No se pudo conectar con Active Directory.");
                });
            }
        }, "login-auth");
        t.setDaemon(true);
        t.start();
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
