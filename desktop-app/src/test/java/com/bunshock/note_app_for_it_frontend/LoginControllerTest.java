package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.bunshock.note_app_for_it_frontend.controllers.LoginController;
import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.models.AdCredentialResult;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.IADService;
import com.bunshock.note_app_for_it_frontend.services.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.MockADService;
import com.bunshock.note_app_for_it_frontend.services.MockAuditService;
import com.bunshock.note_app_for_it_frontend.services.MockUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import java.util.List;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Exercises the real handleLogin() flow (MockADService stands in for the AD API's
// validate-credentials endpoint, which doesn't exist yet — see IADService.validateCredentials)
// via reflection-injected fields, no real Stage/Scene — same "no Stage.show() in the permanent
// suite" convention already established elsewhere in this suite for ComboBox/Skin flakiness,
// which doesn't even apply here since LoginController touches no ComboBox, but a real window is
// unnecessary for what this test needs to prove.
class LoginControllerTest {

    private static final String MOCK_PASSWORD = "password123";

    private LoginController controller;
    private TextField txtUsername;
    private PasswordField pfPassword;
    private Label lblLoginStatus;
    // IUserRoleService is read-only in production (an admin edits USER_ROLE via SQL, not app
    // code) — kept as its concrete Mock type here so tests can still seed a role, via
    // MockUserRoleService's own test-only setRole(), not part of the interface.
    private MockUserRoleService mockUserRoleService;
    private MockAuditService mockAuditService;

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
        ServiceLocator.getInstance().setAdService(new MockADService());
        mockUserRoleService = new MockUserRoleService();
        ServiceLocator.getInstance().setUserRoleService(mockUserRoleService);
        mockAuditService = new MockAuditService();
        ServiceLocator.getInstance().setAuditService(mockAuditService);

        ConfigService.getInstance().load();
        // Deterministic regardless of whatever's in this machine's real app-config.json — the
        // group-gate tests below set this explicitly per case instead.
        ConfigService.getInstance().getConfig().adAccess.allowedGroupName = null;

        controller = new LoginController();
        txtUsername = new TextField();
        pfPassword = new PasswordField();
        lblLoginStatus = new Label();
        setField("txtUsername", txtUsername);
        setField("pfPassword", pfPassword);
        setField("lblLoginStatus", lblLoginStatus);
        setField("btnLogin", new Button());
        setField("btnExit", new Button());
    }

    @AfterEach
    void tearDown() {
        TechnicianSessionService.getInstance().applyManualOverride(null, null, null, null);
        AdminSession.getInstance().deactivate();
    }

    @Test
    void successfulLoginWithAdminRolePopulatesSessionAndActivatesAdminMode() throws Exception {
        mockUserRoleService.setRole("jperez", IUserRoleService.ROLE_ADMIN);
        CountDownLatch latch = new CountDownLatch(1);

        runOnFx(() -> {
            txtUsername.setText("jperez");
            pfPassword.setText(MOCK_PASSWORD);
            controller.setOnLoginSuccess(latch::countDown);
            invoke("handleLogin");
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "login did not complete in time");
        assertEquals("jperez", TechnicianSessionService.getInstance().getUsername());
        assertEquals(IUserRoleService.ROLE_ADMIN, TechnicianSessionService.getInstance().getRole());
        assertTrue(AdminSession.getInstance().isActive());

        assertEquals(1, mockAuditService.getAllLogins().size());
        assertTrue(mockAuditService.getAllLogins().get(0).isSuccess());
        assertEquals("jperez", mockAuditService.getAllLogins().get(0).getUsername());
    }

    @Test
    void successfulLoginWithUserRoleDoesNotActivateAdminMode() throws Exception {
        mockUserRoleService.setRole("jperez", IUserRoleService.ROLE_USER);
        CountDownLatch latch = new CountDownLatch(1);

        runOnFx(() -> {
            txtUsername.setText("jperez");
            pfPassword.setText(MOCK_PASSWORD);
            controller.setOnLoginSuccess(latch::countDown);
            invoke("handleLogin");
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "login did not complete in time");
        assertFalse(AdminSession.getInstance().isActive());

        assertEquals(1, mockAuditService.getAllLogins().size());
        assertTrue(mockAuditService.getAllLogins().get(0).isSuccess());
    }

    @Test
    void wrongPasswordShowsErrorAndDoesNotPopulateSession() throws Exception {
        runOnFx(() -> {
            txtUsername.setText("jperez");
            pfPassword.setText("wrong-password");
            invoke("handleLogin");
        });

        waitUntilStatusContains("incorrectos");
        assertFalse(TechnicianSessionService.getInstance().isResolved());
        waitUntilAuditHasEntry();
        assertFalse(mockAuditService.getAllLogins().get(0).isSuccess());
        assertEquals("INVALID_CREDENTIALS", mockAuditService.getAllLogins().get(0).getFailureReason());
    }

    @Test
    void unknownUsernameShowsErrorAndDoesNotPopulateSession() throws Exception {
        runOnFx(() -> {
            txtUsername.setText("nobody");
            pfPassword.setText(MOCK_PASSWORD);
            invoke("handleLogin");
        });

        waitUntilStatusContains("incorrectos");
        assertFalse(TechnicianSessionService.getInstance().isResolved());
        waitUntilAuditHasEntry();
        assertFalse(mockAuditService.getAllLogins().get(0).isSuccess());
        assertEquals("INVALID_CREDENTIALS", mockAuditService.getAllLogins().get(0).getFailureReason());
    }

    @Test
    void validCredentialsButNotInAllowedGroupIsRejected() throws Exception {
        ConfigService.getInstance().getConfig().adAccess.allowedGroupName = "SomeOtherGroup";

        runOnFx(() -> {
            txtUsername.setText("jperez");
            pfPassword.setText(MOCK_PASSWORD);
            invoke("handleLogin");
        });

        waitUntilStatusContains("permisos");
        assertFalse(TechnicianSessionService.getInstance().isResolved());
        waitUntilAuditHasEntry();
        assertFalse(mockAuditService.getAllLogins().get(0).isSuccess());
        assertEquals("NOT_IN_ALLOWED_GROUP", mockAuditService.getAllLogins().get(0).getFailureReason());
    }

    @Test
    void profileLookupFailureIsLoggedWithItsOwnReason() throws Exception {
        // A custom IADService double whose validateCredentials() always passes but search()
        // always comes back empty — reproduces the "credentials fine, but AD has no matching
        // profile" branch, which MockADService's own fixed user list can never trigger since
        // search() there always finds the same user validateCredentials() just approved.
        ServiceLocator.getInstance().setAdService(new IADService() {
            @Override public List<ADUser> search(String dni, String name, String username) { return List.of(); }
            @Override public AdCredentialResult validateCredentials(String username, String password) {
                return new AdCredentialResult(true, List.of());
            }
        });

        runOnFx(() -> {
            txtUsername.setText("jperez");
            pfPassword.setText(MOCK_PASSWORD);
            invoke("handleLogin");
        });

        waitUntilStatusContains("perfil");
        assertFalse(TechnicianSessionService.getInstance().isResolved());
        waitUntilAuditHasEntry();
        assertFalse(mockAuditService.getAllLogins().get(0).isSuccess());
        assertEquals("PROFILE_LOOKUP_FAILED", mockAuditService.getAllLogins().get(0).getFailureReason());
    }

    @Test
    void adUnreachableIsLoggedWithItsOwnReason() throws Exception {
        ServiceLocator.getInstance().setAdService(new IADService() {
            @Override public List<ADUser> search(String dni, String name, String username) { return List.of(); }
            @Override public AdCredentialResult validateCredentials(String username, String password) {
                throw new RuntimeException("AD API unreachable");
            }
        });

        runOnFx(() -> {
            txtUsername.setText("jperez");
            pfPassword.setText(MOCK_PASSWORD);
            invoke("handleLogin");
        });

        waitUntilStatusContains("Active Directory");
        assertFalse(TechnicianSessionService.getInstance().isResolved());
        waitUntilAuditHasEntry();
        assertFalse(mockAuditService.getAllLogins().get(0).isSuccess());
        assertEquals("AD_UNREACHABLE", mockAuditService.getAllLogins().get(0).getFailureReason());
    }

    // ── helpers ──────────────────────────────────────────────────────

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
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "FX action did not complete in time");
    }

    // handleLogin() runs its network/service call on a background thread and reports back via
    // Platform.runLater — for the error paths (no onLoginSuccess callback fires), poll the
    // status label until it settles instead of guessing a fixed sleep duration.
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

    // logLoginAttempt() runs on the same background thread, before the Platform.runLater() call
    // waitUntilStatusContains() already waited on — so in practice the entry is already there by
    // the time that method returns. Polling here too costs nothing and keeps this test robust
    // against any future reordering of those two calls.
    private void waitUntilAuditHasEntry() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (!mockAuditService.getAllLogins().isEmpty()) return;
            Thread.sleep(50);
        }
        fail("No LOGIN_AUDIT entry was recorded");
    }
}
