package com.bunshock.note_app_for_it_frontend.services.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code IADService} over the middleware's {@code /api/v1/directory/users} (Phase B). Thin by
 * design — unlike the retired {@code AdApiService}, all the fan-out/merge/re-verify logic against
 * the org's loosely-matching directory API now lives server-side in the middleware's own
 * {@code DirectoryService} (a deliberate port of that same logic, not a thin proxy — see
 * {@code IMPLEMENTED_ENDPOINTS.md}'s Directory section on the middleware). This class just builds
 * the query string and maps the response.
 */
public class RestDirectoryService implements IADService {

    private static final String PATH = "/api/v1/directory/users";

    private final MiddlewareClient client;

    public RestDirectoryService(MiddlewareClient client) {
        this.client = client;
    }

    @Override
    public List<ADUser> search(String dni, String name, String username) {
        // Mirrors the retired AdApiService's own short-circuit — an all-blank query never even
        // reaches the middleware, which would otherwise reject it with 400 MISSING_SEARCH_CRITERIA.
        if (isBlank(dni) && isBlank(name) && isBlank(username)) return List.of();

        StringBuilder qs = new StringBuilder();
        appendParam(qs, "dni", dni);
        appendParam(qs, "name", name);
        appendParam(qs, "username", username);

        ResultsDto dto = client.get(PATH + (qs.length() > 0 ? "?" + qs : ""), ResultsDto.class);
        return dto.results().stream()
                .map(u -> new ADUser(u.dni(), u.fullName(), u.username(), u.email(), u.ou()))
                .collect(Collectors.toList());
    }

    private static void appendParam(StringBuilder qs, String key, String value) {
        if (isBlank(value)) return;
        if (qs.length() > 0) qs.append('&');
        qs.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserDto(String username, String fullName, String email, String dni, String ou) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResultsDto(List<UserDto> results) {}
}
