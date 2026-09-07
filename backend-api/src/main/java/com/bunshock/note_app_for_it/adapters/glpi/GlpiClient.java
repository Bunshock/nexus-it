package com.bunshock.note_app_for_it.adapters.glpi;

import com.bunshock.note_app_for_it.common.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Low-level GLPI HL API session handling. GLPI auth (F1, confirmed 2026-09-07) = a shared
 * {@code App-Token} (deployment config) + the acting technician's own {@code user_token} →
 * {@code Session-Token} from {@code GET /initSession}. The per-user {@code user_token} is
 * resolved by {@link GlpiUserTokenResolver}; this class just does the session dance.
 *
 * <p><b>Skeleton</b>: the request shapes below are real, but nothing calls {@code openSession()}
 * yet — the {@code port} adapters are all {@code NOT_IMPLEMENTED} stubs. Untested against a live
 * GLPI.
 */
@Component
public class GlpiClient {

    private final GlpiAdapterProperties properties;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public GlpiClient(GlpiAdapterProperties properties) {
        this.properties = properties;
    }

    /** Opens a GLPI session as the given technician's {@code user_token}. Returns the {@code Session-Token}. */
    public String openSession(String userToken) {
        requireConfigured();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/initSession"))
                    .header("App-Token", properties.getAppToken())
                    .header("Authorization", "user_token " + userToken)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw glpiUnavailable("initSession → HTTP " + response.statusCode());
            }
            JsonNode node = mapper.readTree(response.body());
            String sessionToken = node.path("session_token").asText(null);
            if (sessionToken == null || sessionToken.isBlank()) {
                throw glpiUnavailable("initSession returned no session_token");
            }
            return sessionToken;
        } catch (IOException e) {
            throw glpiUnavailable(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw glpiUnavailable("interrupted");
        }
    }

    /** Best-effort session close — never throws (a leaked GLPI session expires on its own). */
    public void closeSession(String sessionToken) {
        if (!properties.isConfigured() || sessionToken == null) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/killSession"))
                    .header("App-Token", properties.getAppToken())
                    .header("Session-Token", sessionToken)
                    .GET()
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // swallow
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GLPI_NOT_CONFIGURED",
                    "El adaptador GLPI todavía no está configurado.");
        }
    }

    private String base() {
        String url = properties.getBaseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static ApiException glpiUnavailable(String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GLPI_UNAVAILABLE",
                "No se pudo contactar a GLPI. " + (detail == null ? "" : "(" + detail + ")"));
    }
}
