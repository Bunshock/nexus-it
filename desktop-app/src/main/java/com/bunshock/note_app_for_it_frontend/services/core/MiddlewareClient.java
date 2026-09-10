package com.bunshock.note_app_for_it_frontend.services.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;

/**
 * The single low-level HTTP conduit to the middleware (Phase B). Owns the base URL and the opaque
 * session token (in memory only — never persisted). Every REST-backed service in the app goes
 * through this one instance so auth, error-envelope decoding, and the {@code 401 -> re-login}
 * hook live in exactly one place.
 *
 * <p>Calls block; callers already run them off the FX thread. Hard-online by design — no retry,
 * no offline queue: a transport failure is a {@link MiddlewareException} the caller surfaces.
 */
public class MiddlewareClient {

    private static MiddlewareClient instance;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String baseUrl;
    private volatile String sessionToken;
    private volatile Runnable onSessionExpired;

    private MiddlewareClient() {}

    public static MiddlewareClient getInstance() {
        if (instance == null) instance = new MiddlewareClient();
        return instance;
    }

    /** {@code config.middleware.baseUrl}. Trailing slash trimmed so {@code path} args start with one. */
    public void configure(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            this.baseUrl = null;
            return;
        }
        String trimmed = baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public boolean isConfigured() {
        return baseUrl != null;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setSessionToken(String token) {
        this.sessionToken = token;
    }

    public void clearSessionToken() {
        this.sessionToken = null;
    }

    public boolean hasSession() {
        return sessionToken != null;
    }

    /** Invoked once (on the FX thread) the first time a request 401s with a session attached —
     * the app wires this to return to the login screen. */
    public void setOnSessionExpired(Runnable callback) {
        this.onSessionExpired = callback;
    }

    // ── typed calls ─────────────────────────────────────────────────────────

    public <T> T get(String path, Class<T> responseType) {
        return exchange("GET", path, null, responseType, null);
    }

    public <T> T post(String path, Object body, Class<T> responseType) {
        return exchange("POST", path, body, responseType, null);
    }

    public void post(String path, Object body) {
        exchange("POST", path, body, Void.class, null);
    }

    /** For {@code POST /auth/login}: authenticates with a caller-supplied bearer (the IdP access
     * token), not the session token — there is no session yet. */
    public <T> T postWithBearer(String path, Object body, Class<T> responseType, String bearer) {
        return exchange("POST", path, body, responseType, bearer);
    }

    // ── core ───────────────────────────────────────────────────────────────

    private <T> T exchange(String method, String path, Object body, Class<T> responseType, String bearerOverride) {
        if (baseUrl == null) {
            throw new MiddlewareException(0, "NOT_CONFIGURED", "El servidor no está configurado (middleware.baseUrl).");
        }
        boolean sessionAttached = bearerOverride == null && sessionToken != null;

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        String bearer = bearerOverride != null ? bearerOverride : sessionToken;
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new MiddlewareException(0, "TRANSPORT", "No se pudo conectar con el servidor.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MiddlewareException(0, "TRANSPORT", "La conexión con el servidor fue interrumpida.", e);
        }

        int status = response.statusCode();
        String raw = response.body();

        if (status >= 200 && status < 300) {
            if (responseType == Void.class || raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return mapper.readValue(raw, responseType);
            } catch (Exception e) {
                throw new MiddlewareException(status, "PARSE", "Respuesta del servidor ilegible.", e);
            }
        }

        if (status == 401 && sessionAttached) {
            clearSessionToken();
            Runnable hook = onSessionExpired;
            if (hook != null) {
                Platform.runLater(hook);
            }
        }

        throw toEnvelopeException(status, raw);
    }

    private MiddlewareException toEnvelopeException(int status, String raw) {
        try {
            JsonNode error = mapper.readTree(raw).path("error");
            String code = error.path("code").asText(null);
            String message = error.path("message").asText(null);
            if (code != null) {
                return new MiddlewareException(status, code,
                        message != null ? message : "Error del servidor (" + status + ").");
            }
        } catch (Exception ignored) {
            // body wasn't the standard envelope
        }
        return new MiddlewareException(status, "HTTP_" + status, "Error del servidor (" + status + ").");
    }

    private String writeJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new MiddlewareException(0, "SERIALIZE", "No se pudo serializar la solicitud.", e);
        }
    }
}
