package com.bunshock.note_app_for_it_frontend.services.auth;

import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.auth.AuthConfig;
import com.bunshock.note_app_for_it_frontend.models.auth.MiddlewareStatus;
import com.bunshock.note_app_for_it_frontend.models.auth.SessionInfo;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareClient;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareException;

/**
 * The middleware's auth endpoints, as the desktop client uses them. All calls block and throw
 * {@link MiddlewareException} on failure — {@code LoginController} runs them off the FX thread.
 *
 * <p>{@link #login} / {@link #devLogin} store the returned session token into
 * {@link MiddlewareClient} on success, so every later REST call is authenticated automatically.
 */
public class MiddlewareAuthService {

    private static final String CONFIG_PATH    = "/api/v1/auth/config";
    private static final String LOGIN_PATH     = "/api/v1/auth/login";
    private static final String DEV_LOGIN_PATH = "/api/v1/auth/dev-login";
    private static final String LOGOUT_PATH    = "/api/v1/auth/logout";
    private static final String STATUS_PATH    = "/api/v1/status";

    private static MiddlewareAuthService instance;

    private MiddlewareAuthService() {}

    public static MiddlewareAuthService getInstance() {
        if (instance == null) instance = new MiddlewareAuthService();
        return instance;
    }

    private MiddlewareClient client() {
        return MiddlewareClient.getInstance();
    }

    /**
     * {@code GET /auth/config}. Throws {@code MiddlewareException(503, "IDP_NOT_CONFIGURED", …)}
     * when the middleware has no IdP wired — the caller reads that as "use dev-login".
     */
    public AuthConfig fetchAuthConfig() {
        return client().get(CONFIG_PATH, AuthConfig.class);
    }

    /** Exchanges an IdP access token for a middleware session. */
    public SessionInfo login(String idpAccessToken) {
        SessionInfo info = client().postWithBearer(LOGIN_PATH, null, SessionInfo.class, idpAccessToken);
        client().setSessionToken(info.sessionToken());
        return info;
    }

    /** Dev-profile only ({@code 404} otherwise) — mints a session for a seeded user, no IdP. */
    public SessionInfo devLogin(String username) {
        SessionInfo info = client().post(DEV_LOGIN_PATH, Map.of("username", username), SessionInfo.class);
        client().setSessionToken(info.sessionToken());
        return info;
    }

    /** Best-effort server-side revocation, then always clears the local token. */
    public void logout() {
        try {
            client().post(LOGOUT_PATH, null);
        } catch (MiddlewareException ignored) {
            // logging out is best-effort — a dead server still means the session is over locally
        } finally {
            client().clearSessionToken();
        }
    }

    public MiddlewareStatus status() {
        return client().get(STATUS_PATH, MiddlewareStatus.class);
    }
}
