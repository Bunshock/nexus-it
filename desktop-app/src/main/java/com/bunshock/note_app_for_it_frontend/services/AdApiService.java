package com.bunshock.note_app_for_it_frontend.services;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AdApiService implements IADService {

    private static final String USERS_PATH = "/api/v1/ad/users";

    private static AdApiService instance;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private String baseUrl;
    private String apiToken;

    private AdApiService() {}

    public static AdApiService getInstance() {
        if (instance == null) instance = new AdApiService();
        return instance;
    }

    public void configure(String baseUrl, String apiToken) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
    }

    @Override
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiToken != null && !apiToken.isBlank();
    }

    @Override
    public List<ADUser> search(String dni, String name, String username) {
        if (!isConfigured()) return List.of();
        if (isBlank(dni) && isBlank(name) && isBlank(username)) return List.of();

        List<String> dniVariants = dniVariants(dni);
        List<String> nameVariants = nameVariants(name);
        String usernameValue = isBlank(username) ? null : username.trim().toLowerCase();

        Map<String, AdApiUserDto> merged = new LinkedHashMap<>();
        for (String d : dniVariants) {
            for (String n : nameVariants) {
                for (AdApiUserDto dto : query(baseUrl, apiToken, d, n, usernameValue, httpClient, mapper)) {
                    merged.putIfAbsent(dto.samAccountName, dto);
                }
            }
        }
        return merged.values().stream().map(AdApiService::toADUser).toList();
    }

    /** Tests arbitrary connection details without mutating the singleton's live config. */
    public boolean testConnection(String testUrl, String testToken, String testUsername) {
        if (testUrl == null || testUrl.isBlank() || testToken == null || testToken.isBlank()) return false;
        try {
            query(testUrl, testToken, null, null,
                isBlank(testUsername) ? "connection-test" : testUsername.trim().toLowerCase(),
                httpClient, mapper);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<AdApiUserDto> query(String baseUrl, String apiToken, String dni, String name,
                                             String username, HttpClient httpClient, ObjectMapper mapper) {
        try {
            StringBuilder qs = new StringBuilder();
            appendParam(qs, "dni", dni);
            appendParam(qs, "name", name);
            appendParam(qs, "username", username);

            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            String url = base + USERS_PATH + (qs.length() > 0 ? "?" + qs : "");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiToken)
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return mapper.readValue(response.body(), new TypeReference<List<AdApiUserDto>>() {});
            }
            throw new RuntimeException("AD API request failed with status " + response.statusCode()
                + ": " + response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("AD API request failed", e);
        }
    }

    private static void appendParam(StringBuilder qs, String key, String value) {
        if (value == null) return;
        if (qs.length() > 0) qs.append('&');
        qs.append(key).append('=').append(urlEncode(value));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%2C", ",");
    }

    /** Non-blank DNI yields [digitsOnly] or [digitsOnly, dotted] when dotting actually changes the value. */
    private static List<String> dniVariants(String dni) {
        if (isBlank(dni)) return singleNullList();
        String digits = dni.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return singleNullList();
        String dotted = dotFormat(digits);
        return dotted.equals(digits) ? List.of(digits) : List.of(digits, dotted);
    }

    /** Groups digits by 3 from the right, e.g. "45933368" -> "45.933.368". */
    private static String dotFormat(String digits) {
        StringBuilder sb = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) sb.append('.');
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Non-blank name yields the text as typed (matches stored "Apellido, Nombre(s)" as a
     * contiguous substring whenever the given names were typed in their stored order) and,
     * when there are 2+ words, a reordered "lastWord, restOfWords" guess (matches the common
     * "Nombre Apellido" input). Both are lowercased since the API expects lowercase.
     */
    private static List<String> nameVariants(String name) {
        if (isBlank(name)) return singleNullList();
        String asTyped = name.trim().toLowerCase();
        String[] words = asTyped.split("\\s+");
        if (words.length < 2) return List.of(asTyped);

        String rest = String.join(" ", Arrays.copyOfRange(words, 0, words.length - 1));
        String reordered = words[words.length - 1] + ", " + rest;
        return reordered.equals(asTyped) ? List.of(asTyped) : List.of(asTyped, reordered);
    }

    private static List<String> singleNullList() {
        List<String> l = new ArrayList<>(1);
        l.add(null);
        return l;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static ADUser toADUser(AdApiUserDto dto) {
        return new ADUser(extractDni(dto.dni), dto.displayName, dto.samAccountName, dto.mail, dto.ou);
    }

    /**
     * Some AD accounts return "dni" as a JSON array instead of a plain string (a multi-valued
     * directory attribute) — use the first value, or "" if the array is empty. A missing/null
     * field stays null, same as before this was discovered.
     */
    private static String extractDni(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) return node.size() > 0 ? node.get(0).asText() : "";
        return node.asText();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AdApiUserDto {
        public String samAccountName;
        public String displayName;
        public JsonNode dni;
        public String mail;
        public String ou;
    }
}
