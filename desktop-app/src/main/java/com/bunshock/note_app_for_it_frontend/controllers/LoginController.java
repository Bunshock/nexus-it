package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.models.auth.AdCredentialResult;
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
import javafx.scene.control.TextFormatter;

/**
 * Standalone login screen shown before MainView (and its startup connectivity overlay) is
 * even constructed — see App.java. Validates AD credentials, then AD group membership (app
 * access gate), then resolves the exact-matching AD profile, then requires an APP_USER row to
 * exist at all (a valid AD account with no row is not permitted to use the app), then reads its
 * role/Sede, populating TechnicianSessionService exactly once every check passes. An ADMIN- or
 * SUPERADMIN-role login activates AdminSession immediately (no separate password prompt — this
 * login already proved identity).
 */
public class LoginController {

    @FXML private TextField txtUsername;
    @FXML private PasswordField pfPassword;
    @FXML private Label lblLoginStatus;
    @FXML private Button btnLogin;
    @FXML private Button btnExit;

    private Runnable onLoginSuccess;

    // Neither field is persisted (username is only ever compared to AD's own records, password
    // only ever sent to the AD validate-credentials call) — capped purely as a sanity guard
    // against an accidental huge paste, same reasoning as UserNoteController's AD-lookup field.
    private static final int LOGIN_FIELD_MAX_LENGTH = 100;

    // Rate-limits repeated failed attempts against AUDIT_LOGIN itself, not a separate lockout
    // table — a blocked attempt below never calls validateCredentials() and never inserts a new
    // AUDIT_LOGIN row, so the table's own growth is bounded by MAX_FAILED_ATTEMPTS per rolling
    // window per username, not by how many times someone keeps clicking "Iniciar sesión".
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_WINDOW_MINUTES = 15;

    public void setOnLoginSuccess(Runnable onLoginSuccess) {
        this.onLoginSuccess = onLoginSuccess;
    }

    public void initialize() {
        txtUsername.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= LOGIN_FIELD_MAX_LENGTH ? change : null));
        pfPassword.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= LOGIN_FIELD_MAX_LENGTH ? change : null));

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
            IAuditService audit = ServiceLocator.getInstance().getAuditService();
            try {
                // Checked before anything else, including the AD call itself — a locked-out
                // attempt never reaches AD and never inserts a new AUDIT_LOGIN row, which is what
                // actually bounds that table's growth (the point of this check in the first
                // place), not just a UI-level annoyance.
                int recentFailures = audit.countRecentFailedLoginAttempts(username, LOCKOUT_WINDOW_MINUTES);
                if (recentFailures >= MAX_FAILED_ATTEMPTS) {
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("Demasiados intentos fallidos. Intente nuevamente en unos minutos.");
                    });
                    return;
                }

                AdCredentialResult credentials = ServiceLocator.getInstance().getAdService()
                    .validateCredentials(username, password);

                if (!credentials.isValid()) {
                    audit.recordLoginAttempt(username, false, "Credenciales inválidas");
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("Usuario o contraseña incorrectos.");
                    });
                    return;
                }

                String allowedGroup = allowedGroupName();
                boolean inAllowedGroup = allowedGroup == null || credentials.getGroups().contains(allowedGroup);
                // A specific account (e.g. an intern technician) can be excepted from the group
                // requirement via APP_USER.bypass_group_check — set only by a superadmin via
                // direct SQL, same "explicit per-account grant" convention as role/sede_id.
                // Registration in APP_USER is still separately and unconditionally required below
                // either way; this only overrides the group-membership gate specifically.
                if (!inAllowedGroup && !ServiceLocator.getInstance().getUserRoleService().hasGroupCheckBypass(username)) {
                    audit.recordLoginAttempt(username, false, "No pertenece al grupo permitido");
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("No tiene permisos para usar esta aplicación.");
                    });
                    return;
                }

                // Exact match required — search() does substring matching (needed elsewhere for
                // partial-username recipient lookups), but resolving the just-authenticated
                // account must never land on a different account that merely contains what was
                // typed as a substring.
                List<ADUser> profile = ServiceLocator.getInstance().getAdService().search(null, null, username);
                ADUser user = profile.stream()
                    .filter(u -> u.getUsername().equalsIgnoreCase(username))
                    .findFirst()
                    .orElse(null);
                if (user == null) {
                    audit.recordLoginAttempt(username, false, "Perfil no encontrado en Active Directory");
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("No se pudo obtener el perfil desde Active Directory.");
                    });
                    return;
                }

                // A valid AD account with no APP_USER row must not be let in at all — getRole()'s
                // ROLE_USER default is a permission fallback for already-registered accounts, not
                // an implicit "anyone with valid AD credentials may use the app" gate.
                if (!ServiceLocator.getInstance().getUserRoleService().isRegistered(user.getUsername())) {
                    audit.recordLoginAttempt(username, false, "Usuario no registrado en la aplicación");
                    Platform.runLater(() -> {
                        setBusy(false);
                        showError("Usuario no registrado en la aplicación. Solicite acceso a un administrador.");
                    });
                    return;
                }

                String role = ServiceLocator.getInstance().getUserRoleService().getRole(user.getUsername());
                audit.recordLoginAttempt(username, true, null);

                Platform.runLater(() -> {
                    TechnicianSessionService.getInstance().loginResolved(user, role);
                    if (IUserRoleService.ROLE_ADMIN.equals(role) || IUserRoleService.ROLE_SUPERADMIN.equals(role)) {
                        AdminSession.getInstance().activatePermanently(role);
                    }
                    if (onLoginSuccess != null) onLoginSuccess.run();
                });
            } catch (Exception adUnreachable) {
                // Deliberately not recorded in AUDIT_LOGIN and not counted toward the rate limit
                // above — this is AD/DB connectivity failing, not evidence of the technician
                // guessing credentials, and counting it against them would be unfair.
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
