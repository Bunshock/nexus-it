package com.bunshock.note_app_for_it_frontend.controllers.auth;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.App;
import com.bunshock.note_app_for_it_frontend.models.admin.Permission;
import com.bunshock.note_app_for_it_frontend.models.auth.AuthConfig;
import com.bunshock.note_app_for_it_frontend.models.auth.Roles;
import com.bunshock.note_app_for_it_frontend.models.auth.SessionInfo;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.auth.MiddlewareAuthService;
import com.bunshock.note_app_for_it_frontend.services.auth.OidcBrowserFlow;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.services.auth.WindowsIdentityService;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareException;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.VBox;

/**
 * Login screen — Phase B. On show it probes {@code GET /auth/config}:
 * <ul>
 *   <li>IdP configured &rArr; <b>production mode</b>: no button to click — the system-browser
 *       OIDC flow ({@link OidcBrowserFlow}) starts immediately, showing a progress spinner
 *       ("Iniciando sesión..."). On failure the screen switches to a retry state ("Reintentar" /
 *       "Cerrar"); on success {@code POST /auth/login} completes and the screen closes.</li>
 *   <li>{@code 503 IDP_NOT_CONFIGURED} &rArr; <b>dev mode</b>, unchanged: a username field and an
 *       explicit "Iniciar sesión" button that calls {@code POST /auth/dev-login} (no Keycloak,
 *       seeded users only).</li>
 * </ul>
 * All credential checks, the group gate, the {@code APP_USER} registration check, role
 * resolution, and the {@code AUDIT_LOGIN} write happen server-side now — this controller only
 * unpacks the session response.
 */
public class LoginController {

    @FXML private VBox devUsernameBox;
    @FXML private TextField txtUsername;
    @FXML private Label lblModeHint;
    @FXML private ProgressIndicator progressLogin;
    @FXML private Label lblLoginStatus;
    @FXML private Button btnLogin;
    @FXML private Button btnExit;

    private Runnable onLoginSuccess;

    private boolean devMode;
    private AuthConfig authConfig;

    // Sanity guard against an accidental huge paste — the username is only ever sent to
    // /auth/dev-login, never persisted.
    private static final int USERNAME_MAX_LENGTH = 100;

    public void setOnLoginSuccess(Runnable onLoginSuccess) {
        this.onLoginSuccess = onLoginSuccess;
    }

    public void initialize() {
        txtUsername.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= USERNAME_MAX_LENGTH ? change : null));
        probeAuthConfig();
    }

    // ── auth-config probe (decides dev vs prod) ─────────────────────────────

    private void probeAuthConfig() {
        setBusy(true);
        showBusySpinner(true);
        showStatus("Conectando con el servidor...", "#64748b");
        runOffThread("login-probe", () -> {
            try {
                AuthConfig cfg = MiddlewareAuthService.getInstance().fetchAuthConfig();
                Platform.runLater(() -> enterProdMode(cfg));
            } catch (MiddlewareException e) {
                Platform.runLater(() -> {
                    if (e.getStatus() == 503 && e.isCode("IDP_NOT_CONFIGURED")) {
                        enterDevMode();
                    } else if (e.isTransport()) {
                        showRetryable("No se pudo conectar con el servidor.");
                    } else {
                        showRetryable(e.getMessage());
                    }
                });
            }
        });
    }

    private void enterProdMode(AuthConfig cfg) {
        this.devMode = false;
        this.authConfig = cfg;
        devUsernameBox.setVisible(false);
        devUsernameBox.setManaged(false);
        lblModeHint.setText("");
        lblModeHint.setVisible(false);
        lblModeHint.setManaged(false);
        btnLogin.setText("Iniciar sesión");
        btnLogin.setOnAction(e -> handleLogin());
        btnExit.setText("Salir");
        clearStatus();
        setBusy(false);
        handleLogin(); // no username to type anymore — start the browser flow immediately
    }

    private void enterDevMode() {
        this.devMode = true;
        this.authConfig = null;
        devUsernameBox.setVisible(true);
        devUsernameBox.setManaged(true);
        lblModeHint.setText("Modo desarrollo — sin Keycloak");
        lblModeHint.setVisible(true);
        lblModeHint.setManaged(true);
        btnLogin.setText("Iniciar sesión");
        btnLogin.setOnAction(e -> handleLogin());
        btnLogin.setVisible(true);
        btnLogin.setManaged(true);
        showBusySpinner(false);
        String sessionEmail = WindowsIdentityService.getInstance().getSessionEmail();
        if (sessionEmail != null && txtUsername.getText().isEmpty()) {
            txtUsername.setText(TechnicianSessionService.deriveUsernameFromEmail(sessionEmail));
        }
        clearStatus();
        setBusy(false);
        Platform.runLater(() -> (txtUsername.getText().isEmpty() ? txtUsername : btnLogin).requestFocus());
    }

    /** The initial {@code GET /auth/config} probe failed at the transport/server level — retry re-probes. */
    private void showRetryable(String message) {
        showBusySpinner(false);
        showError(message);
        btnLogin.setText("Reintentar");
        btnLogin.setOnAction(e -> probeAuthConfig());
        btnLogin.setVisible(true);
        btnLogin.setManaged(true);
        btnLogin.setDisable(false);
        btnExit.setDisable(false);
    }

    /** An actual login attempt (OIDC) failed — retry re-runs {@link #handleLogin()}, not the probe. */
    private void showLoginRetry(String message) {
        showBusySpinner(false);
        showError(message);
        btnLogin.setText("Reintentar");
        btnLogin.setOnAction(e -> handleLogin());
        btnLogin.setVisible(true);
        btnLogin.setManaged(true);
        btnLogin.setDisable(false);
        btnExit.setText("Cerrar");
        btnExit.setDisable(false);
    }

    // ── login ──────────────────────────────────────────────────────────────

    @FXML
    private void handleLogin() {
        if (devMode) {
            String username = txtUsername.getText().trim();
            if (username.isEmpty()) {
                showError("Ingrese un usuario.");
                return;
            }
            setBusy(true);
            showBusySpinner(true);
            showStatus("Iniciando sesión...", "#64748b");
            runOffThread("login-dev", () -> completeLogin(
                    () -> MiddlewareAuthService.getInstance().devLogin(username)));
        } else {
            btnLogin.setVisible(false);
            btnLogin.setManaged(false);
            setBusy(true);
            showBusySpinner(true);
            showStatus("Iniciando sesión...", "#64748b");
            runOffThread("login-oidc", () -> completeLogin(() -> {
                String accessToken = new OidcBrowserFlow().authenticate(authConfig,
                        url -> App.getInstance().openInBrowser(url));
                return MiddlewareAuthService.getInstance().login(accessToken);
            }));
        }
    }

    private interface LoginStep {
        SessionInfo run();
    }

    private void completeLogin(LoginStep step) {
        try {
            SessionInfo info = step.run();
            Platform.runLater(() -> {
                TechnicianSessionService.getInstance().loginResolved(info);
                if (Roles.ADMIN.equals(info.role()) || Roles.SUPERADMIN.equals(info.role())) {
                    AdminSession.getInstance().activatePermanently(info.role(), parsePermissions(info.permissions()));
                }
                if (onLoginSuccess != null) onLoginSuccess.run();
            });
        } catch (MiddlewareException e) {
            Platform.runLater(() -> {
                setBusy(false);
                String msg = e.getMessage() != null ? e.getMessage() : "No se pudo iniciar sesión.";
                if (devMode) {
                    showBusySpinner(false);
                    showError(msg);
                } else {
                    showLoginRetry(msg);
                }
            });
        }
    }

    private static Set<Permission> parsePermissions(List<String> names) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        if (names == null) return out;
        for (String name : names) {
            try {
                out.add(Permission.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // an unrecognized permission string from a newer middleware — ignore, don't break login
            }
        }
        return out;
    }

    @FXML
    private void handleExit() {
        Platform.exit();
    }

    // ── ui helpers ─────────────────────────────────────────────────────────

    private void runOffThread(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean busy) {
        btnLogin.setDisable(busy);
        btnExit.setDisable(busy);
        txtUsername.setDisable(busy);
    }

    private void showBusySpinner(boolean visible) {
        progressLogin.setVisible(visible);
        progressLogin.setManaged(visible);
    }

    private void showStatus(String message, String hexColor) {
        lblLoginStatus.setText(message);
        lblLoginStatus.setStyle("-fx-text-fill: " + hexColor + ";");
    }

    private void clearStatus() {
        lblLoginStatus.setText("");
    }

    private void showError(String message) {
        showStatus(message, "#ef4444");
    }
}
