package com.bunshock.note_app_for_it_frontend.services.auth;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.bunshock.note_app_for_it_frontend.models.auth.AuthConfig;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * OAuth 2.0 Authorization Code + PKCE against the IdP, driven through the <em>system browser</em>
 * (RFC 8252) — the app never sees the user's password. A one-shot loopback HTTP listener on
 * {@code 127.0.0.1:<ephemeral>/callback} catches the redirect; the code is exchanged for an access
 * token, which the caller then hands to {@code POST /auth/login}.
 *
 * <p>Blocking; call off the FX thread. Every failure is a {@link MiddlewareException} with an
 * {@code OIDC_*} code so the login screen can show something meaningful.
 */
public class OidcBrowserFlow {

    /** How the authorize URL gets opened — supplied by the caller (JavaFX {@code HostServices}). */
    public interface BrowserOpener {
        void open(String url) throws Exception;
    }

    private static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(5);
    private static final SecureRandom RNG = new SecureRandom();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Returns the IdP access token, or throws {@link MiddlewareException}. */
    public String authenticate(AuthConfig cfg, BrowserOpener opener) {
        Endpoints ep = discover(cfg.issuer());

        String verifier = randomUrlSafe(64);
        String challenge = s256(verifier);
        String state = randomUrlSafe(32);

        HttpServer server = startCallbackServer();
        int port = server.getAddress().getPort();
        String redirectUri = "http://127.0.0.1:" + port + "/callback";
        CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

        server.createContext("/callback", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            byte[] html = ("<!doctype html><html lang=\"es\"><meta charset=\"utf-8\"><body style=\""
                    + "font-family:sans-serif;text-align:center;padding:3em;color:#334155\">"
                    + "<h2>Inicio de sesión completado</h2>"
                    + "<p>Podés cerrar esta pestaña y volver a la aplicación.</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
            callback.complete(params);
        });

        try {
            String scope = (cfg.scopes() == null || cfg.scopes().isEmpty())
                    ? "openid profile" : String.join(" ", cfg.scopes());
            Map<String, String> authParams = new LinkedHashMap<>();
            authParams.put("client_id", cfg.clientId());
            authParams.put("response_type", "code");
            authParams.put("scope", scope);
            authParams.put("redirect_uri", redirectUri);
            authParams.put("state", state);
            authParams.put("code_challenge", challenge);
            authParams.put("code_challenge_method", "S256");

            try {
                opener.open(ep.authorization() + "?" + form(authParams));
            } catch (Exception e) {
                throw new MiddlewareException(0, "OIDC_BROWSER",
                        "No se pudo abrir el navegador para iniciar sesión.", e);
            }

            Map<String, String> params;
            try {
                params = callback.get(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw new MiddlewareException(0, "OIDC_TIMEOUT",
                        "El inicio de sesión no se completó a tiempo. Intente nuevamente.");
            } catch (Exception e) {
                throw new MiddlewareException(0, "OIDC_ERROR", "El inicio de sesión fue interrumpido.", e);
            }

            if (params.containsKey("error")) {
                throw new MiddlewareException(0, "OIDC_ERROR",
                        "El proveedor de identidad rechazó el inicio de sesión (" + params.get("error") + ").");
            }
            if (!state.equals(params.get("state"))) {
                throw new MiddlewareException(0, "OIDC_STATE_MISMATCH",
                        "Respuesta de inicio de sesión inválida.");
            }
            String code = params.get("code");
            if (code == null || code.isBlank()) {
                throw new MiddlewareException(0, "OIDC_NO_CODE",
                        "El proveedor de identidad no devolvió un código de autorización.");
            }
            return exchangeCodeForToken(ep.token(), cfg.clientId(), code, redirectUri, verifier);
        } finally {
            server.stop(0);
        }
    }

    private record Endpoints(String authorization, String token) {}

    private Endpoints discover(String issuer) {
        String base = trimSlash(issuer);
        HttpResponse<String> res = send(HttpRequest.newBuilder(
                        URI.create(base + "/.well-known/openid-configuration"))
                .timeout(Duration.ofSeconds(15)).GET().build());
        if (res.statusCode() != 200) {
            throw new MiddlewareException(0, "OIDC_DISCOVERY",
                    "No se pudo obtener la configuración del proveedor de identidad (" + res.statusCode() + ").");
        }
        try {
            JsonNode j = mapper.readTree(res.body());
            String auth = j.path("authorization_endpoint").asText(null);
            String token = j.path("token_endpoint").asText(null);
            if (auth == null || token == null) {
                throw new MiddlewareException(0, "OIDC_DISCOVERY",
                        "La configuración del proveedor de identidad está incompleta.");
            }
            return new Endpoints(auth, token);
        } catch (MiddlewareException e) {
            throw e;
        } catch (Exception e) {
            throw new MiddlewareException(0, "OIDC_DISCOVERY",
                    "La configuración del proveedor de identidad es ilegible.", e);
        }
    }

    private String exchangeCodeForToken(String tokenEndpoint, String clientId, String code,
            String redirectUri, String verifier) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("client_id", clientId);
        body.put("code_verifier", verifier);

        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(body), StandardCharsets.UTF_8))
                .build());
        if (res.statusCode() != 200) {
            throw new MiddlewareException(0, "OIDC_TOKEN",
                    "El proveedor de identidad rechazó el intercambio de código (" + res.statusCode() + ").");
        }
        try {
            String accessToken = mapper.readTree(res.body()).path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new MiddlewareException(0, "OIDC_TOKEN",
                        "El proveedor de identidad no devolvió un token de acceso.");
            }
            return accessToken;
        } catch (MiddlewareException e) {
            throw e;
        } catch (Exception e) {
            throw new MiddlewareException(0, "OIDC_TOKEN", "La respuesta del proveedor de identidad es ilegible.", e);
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new MiddlewareException(0, "TRANSPORT", "No se pudo contactar al proveedor de identidad.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MiddlewareException(0, "TRANSPORT", "La conexión con el proveedor de identidad fue interrumpida.", e);
        }
    }

    private static HttpServer startCallbackServer() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new MiddlewareException(0, "OIDC_CALLBACK",
                    "No se pudo abrir el puerto local para recibir la respuesta de inicio de sesión.", e);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new MiddlewareException(0, "OIDC_PKCE", "No se pudo generar el desafío PKCE.", e);
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return out;
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            String v = i >= 0 ? pair.substring(i + 1) : "";
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
