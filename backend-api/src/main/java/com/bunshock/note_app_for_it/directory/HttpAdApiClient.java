package com.bunshock.note_app_for_it.directory;

import com.bunshock.note_app_for_it.common.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real HTTP implementation of {@link AdApiClient} — ported from the desktop app's
 * {@code AdApiService.query()} (same endpoint path, Bearer auth, comma-preserving query encoding,
 * and array-valued-attribute flattening).
 */
@Component
public class HttpAdApiClient implements AdApiClient {

    private static final String USERS_PATH = "/api/v1/ad/users";

    private final DirectoryProperties properties;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpAdApiClient(DirectoryProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<AdApiUser> fetch(String dni, String name, String username) {
        try {
            StringBuilder qs = new StringBuilder();
            appendParam(qs, "dni", dni);
            appendParam(qs, "name", name);
            appendParam(qs, "username", username);

            String base = properties.getBaseUrl().endsWith("/")
                    ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                    : properties.getBaseUrl();
            String url = base + USERS_PATH + (qs.length() > 0 ? "?" + qs : "");

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + properties.getToken())
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DIRECTORY_UNAVAILABLE",
                        "El directorio respondió con un error (" + response.statusCode() + ").");
            }

            JsonNode array = mapper.readTree(response.body());
            List<AdApiUser> out = new ArrayList<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    out.add(new AdApiUser(
                            extractString(node.get("samAccountName")),
                            extractString(node.get("displayName")),
                            extractString(node.get("dni")),
                            extractString(node.get("mail")),
                            extractString(node.get("ou"))));
                }
            }
            return out;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIRECTORY_UNAVAILABLE",
                    "No se pudo contactar al directorio.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIRECTORY_UNAVAILABLE",
                    "No se pudo contactar al directorio.");
        }
    }

    private static void appendParam(StringBuilder qs, String key, String value) {
        if (value == null) {
            return;
        }
        if (qs.length() > 0) {
            qs.append('&');
        }
        qs.append(key).append('=').append(urlEncode(value));
    }

    // Comma is left literal on purpose — the reordered name variants DirectoryService builds
    // (e.g. "rodriguez, joaquin") must reach the directory API with a real comma.
    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%2C", ",");
    }

    /**
     * Some accounts return a field as a JSON array (a multi-valued attribute — seen on both
     * {@code dni} and {@code mail} for one account). Use the first value, {@code ""} for an empty
     * array, {@code null} for a missing field. Ported verbatim from {@code AdApiService}.
     */
    private static String extractString(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return node.size() > 0 ? node.get(0).asText() : "";
        }
        return node.asText();
    }
}
