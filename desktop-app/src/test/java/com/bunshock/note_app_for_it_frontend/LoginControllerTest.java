package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.bunshock.note_app_for_it_frontend.controllers.auth.LoginController;
import com.bunshock.note_app_for_it_frontend.models.auth.AuthConfig;
import com.bunshock.note_app_for_it_frontend.models.auth.Roles;
import com.bunshock.note_app_for_it_frontend.models.auth.SessionInfo;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.auth.MiddlewareAuthService;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareException;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the Phase B LoginController against a fake {@link MiddlewareAuthService} (all auth is
 * server-side now — no AD / group / role logic here). Reflection-injected FXML fields, no real
 * Stage. The system-browser OIDC flow (prod mode) isn't unit-testable — those tests only check the
 * UI it puts the screen into; the dev-login path is covered end to end.
 */
class LoginControllerTest {

    private LoginController controller;
    private VBox devUsernameBox;
    private TextField txtUsername;
    private Label lblModeHint;
    private Label lblLoginStatus;
    private Button btnLogin;
    private Button btnExit;

    private FakeAuth fakeAuth;

    @BeforeAll
    static void initFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        fakeAuth = new FakeAuth();
        MiddlewareAuthService.setInstanceForTest(fakeAuth);

        controller = new LoginController();
        devUsernameBox = new VBox();
        txtUsername = new TextField();
        lblModeHint = new Label();
        lblLoginStatus = new Label();
        btnLogin = new Button();
        btnExit = new Button();
        setField("devUsernameBox", devUsernameBox);
        setField("txtUsername", txtUsername);
        setField("lblModeHint", lblModeHint);
        setField("lblLoginStatus", lblLoginStatus);
        setField("btnLogin", btnLogin);
        setField("btnExit", btnExit);
    }

    @AfterEach
    void tearDown() {
        MiddlewareAuthService.setInstanceForTest(null);
        TechnicianSessionService.getInstance().clearSessionForLogout();
        AdminSession.getInstance().deactivate();
    }

    // ── dev mode (auth/config -> 503) ──────────────────────────────────────

    @Test
    void devModeShowsUsernameFieldWhenIdpNotConfigured() throws Exception {
        fakeAuth.configError = new MiddlewareException(503, "IDP_NOT_CONFIGURED", "sin IdP");

        runOnFx(() -> controller.initialize());
        // read every field on the FX thread and wait for all of it to settle
        waitUntil(() -> devUsernameBox.isManaged()
                && devUsernameBox.isVisible()
                && lblModeHint.getText() != null
                && lblModeHint.getText().toLowerCase().contains("desarrollo"));
    }

    @Test
    void devLoginWithAdminRolePopulatesSessionAndActivatesAdminMode() throws Exception {
        fakeAuth.configError = new MiddlewareException(503, "IDP_NOT_CONFIGURED", "sin IdP");
        fakeAuth.devLoginResult = session("dev.admin", Roles.ADMIN, List.of("APPROVE_NOTES", "MANAGE_TYPES"));

        CountDownLatch done = new CountDownLatch(1);
        runOnFx(() -> controller.initialize());
        waitUntil(() -> devUsernameBox.isManaged());
        runOnFx(() -> {
            txtUsername.setText("dev.admin");
            controller.setOnLoginSuccess(done::countDown);
            invoke("handleLogin");
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "login did not complete");
        assertEquals("dev.admin", TechnicianSessionService.getInstance().getUsername());
        assertEquals(Roles.ADMIN, TechnicianSessionService.getInstance().getRole());
        assertTrue(AdminSession.getInstance().isActive());
        assertEquals(Roles.ADMIN, AdminSession.getInstance().getEffectiveRole());
    }

    @Test
    void devLoginWithUserRoleDoesNotActivateAdminMode() throws Exception {
        fakeAuth.configError = new MiddlewareException(503, "IDP_NOT_CONFIGURED", "sin IdP");
        fakeAuth.devLoginResult = session("dev.user", Roles.USER, List.of());

        CountDownLatch done = new CountDownLatch(1);
        runOnFx(() -> controller.initialize());
        waitUntil(() -> devUsernameBox.isManaged());
        runOnFx(() -> {
            txtUsername.setText("dev.user");
            controller.setOnLoginSuccess(done::countDown);
            invoke("handleLogin");
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "login did not complete");
        assertFalse(AdminSession.getInstance().isActive());
    }

    @Test
    void devLoginBlankUsernameShowsError() throws Exception {
        fakeAuth.configError = new MiddlewareException(503, "IDP_NOT_CONFIGURED", "sin IdP");
        runOnFx(() -> controller.initialize());
        waitUntil(() -> devUsernameBox.isManaged());

        runOnFx(() -> { txtUsername.setText("  "); invoke("handleLogin"); });
        waitUntilStatusContains("Ingrese un usuario");
        assertFalse(TechnicianSessionService.getInstance().isResolved());
    }

    @Test
    void devLoginServerRejectionShowsTheServerMessage() throws Exception {
        fakeAuth.configError = new MiddlewareException(503, "IDP_NOT_CONFIGURED", "sin IdP");
        fakeAuth.devLoginError = new MiddlewareException(403, "USER_NOT_REGISTERED",
                "Usuario no registrado en la aplicación. Solicite acceso a un administrador.");

        runOnFx(() -> controller.initialize());
        waitUntil(() -> devUsernameBox.isManaged());
        runOnFx(() -> { txtUsername.setText("ghost"); invoke("handleLogin"); });

        waitUntilStatusContains("no registrado");
        assertFalse(TechnicianSessionService.getInstance().isResolved());
        assertFalse(AdminSession.getInstance().isActive());
    }

    // ── prod mode (auth/config OK) ────────────────────────────────────────

    @Test
    void prodModeHidesUsernameFieldWhenIdpIsConfigured() throws Exception {
        fakeAuth.config = new AuthConfig("https://idp.example/realms/x", "nexus-it",
                List.of("openid", "profile"), "Keycloak");

        runOnFx(() -> controller.initialize());
        waitUntil(() -> !btnLogin.isDisabled() && lblLoginStatus.getText().isEmpty());

        assertFalse(devUsernameBox.isVisible());
        assertFalse(devUsernameBox.isManaged());
        assertEquals("Iniciar sesión", btnLogin.getText());
    }

    // ── probe failure ────────────────────────────────────────────────────

    @Test
    void serverUnreachableAtProbeShowsRetryAffordance() throws Exception {
        fakeAuth.configError = new MiddlewareException(0, "TRANSPORT", "No se pudo conectar con el servidor.");

        runOnFx(() -> controller.initialize());
        waitUntilStatusContains("No se pudo conectar");

        String[] label = {null};
        runOnFx(() -> label[0] = btnLogin.getText());
        assertEquals("Reintentar", label[0]);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static SessionInfo session(String username, String role, List<String> permissions) {
        return new SessionInfo("tok-" + username, "2099-01-01T00:00:00Z", username, role,
                1, "Casa Central", username, null, permissions);
    }

    /** A MiddlewareAuthService double — scripted per test, no HTTP. */
    private static class FakeAuth extends MiddlewareAuthService {
        AuthConfig config;
        MiddlewareException configError;
        SessionInfo devLoginResult;
        MiddlewareException devLoginError;

        @Override public AuthConfig fetchAuthConfig() {
            if (configError != null) throw configError;
            return config;
        }
        @Override public SessionInfo devLogin(String username) {
            if (devLoginError != null) throw devLoginError;
            return devLoginResult;
        }
        @Override public void logout() { /* no-op */ }
    }

    private void setField(String name, Object value) throws Exception {
        Field f = LoginController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private void invoke(String methodName) {
        try {
            Method m = LoginController.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            m.invoke(controller);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void runOnFx(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { action.run(); } finally { latch.countDown(); }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "FX action did not complete in time");
    }

    private void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            boolean[] ok = {false};
            runOnFx(() -> ok[0] = condition.getAsBoolean());
            if (ok[0]) return;
            Thread.sleep(50);
        }
        fail("condition not met within timeout");
    }

    private void waitUntilStatusContains(String expectedSubstring) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String[] text = {null};
            runOnFx(() -> text[0] = lblLoginStatus.getText());
            if (text[0] != null && text[0].contains(expectedSubstring)) return;
            Thread.sleep(50);
        }
        fail("lblLoginStatus never showed text containing \"" + expectedSubstring + "\"");
    }
}
