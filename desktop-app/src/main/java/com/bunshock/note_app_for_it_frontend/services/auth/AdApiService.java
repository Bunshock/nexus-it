package com.bunshock.note_app_for_it_frontend.services.auth;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.models.auth.AdCredentialResult;
// ConfigService.getInstance() below reads the same live AppConfig singleton loaded at startup —
// no constructor wiring needed, same pattern SqliteEquipmentService.genericLabel() already uses.
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
public class AdApiService implements IADService {

    private static final String USERS_PATH = "/api/v1/ad/users";
    private static final String VALIDATE_CREDENTIALS_PATH = "/api/v1/ad/validate-credentials";

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
        String usernameValue = isBlank(username) ? null : username.trim().toLowerCase();

        Map<String, AdApiUserDto> merged = runQueries(dniVariants, preciseNameVariants(name), usernameValue);

        // A word that's incomplete anywhere but the very end of a combined guess (e.g. "Joaquin
        // Rodrig" reordered to "Rodrig, Joaquin") breaks the server's substring match, since
        // "Rodrig" isn't immediately followed by ", Joaquin" in the stored "Rodriguez, Joaquin" —
        // only querying that word alone can still match it. Only pay for this extra round when
        // the precise guesses above found nothing, so a well-formed complete name still resolves
        // in one round-trip without pulling in every person who shares a single word.
        if (merged.isEmpty() && !isBlank(name)) {
            List<String> fallback = wordFallbackVariants(name);
            if (!fallback.isEmpty()) {
                merged.putAll(runQueries(dniVariants, fallback, usernameValue));
            }
        }

        // The server can't be trusted to actually AND dni/name/username together, even though
        // every query above sends them combined in one request — confirmed in practice: a
        // name-only search finds the right people, but simply adding a dni to that same search
        // can return an entirely different set of people who only match the dni, never the name
        // (the precise-round query apparently isn't ANDing them server-side either — this isn't
        // limited to the word-fallback round). Re-verify EVERY supplied field client-side against
        // each candidate's own data, unconditionally, regardless of which round produced it and
        // regardless of which fields were supplied — any combination of dni/name/username must
        // ALL match simultaneously. No "fall back to broader results" escape hatch: if a
        // candidate fails even one supplied field, it's excluded, full stop — an empty result
        // means exactly that, no candidate satisfied everything that was asked for.
        List<String> nameWords = isBlank(name) ? List.of() : List.of(name.trim().toLowerCase().split("\\s+"));
        String dniDigits = isBlank(dni) ? null : dni.replaceAll("[^0-9]", "");
        merged.values().removeIf(dto ->
            (!nameWords.isEmpty() && !matchesAllWords(dto, nameWords))
            || (dniDigits != null && !dniDigits.isEmpty() && !matchesDni(dto, dniDigits))
            || (usernameValue != null && !matchesUsername(dto, usernameValue)));

        return merged.values().stream().map(AdApiService::toADUser).toList();
    }

    private static boolean matchesAllWords(AdApiUserDto dto, List<String> words) {
        String displayName = extractString(dto.displayName);
        String haystack = displayName == null ? "" : displayName.toLowerCase();
        return words.stream().allMatch(haystack::contains);
    }

    private static boolean matchesDni(AdApiUserDto dto, String typedDigits) {
        String actual = extractString(dto.dni);
        if (actual == null) return false;
        return actual.replaceAll("[^0-9]", "").startsWith(typedDigits);
    }

    /** Mirrors MockADService's separator-agnostic matching, so a typed "juan.perez" also matches
     * a stored "juan-perez" the same way it would against the mock service in tests. */
    private static boolean matchesUsername(AdApiUserDto dto, String typedUsername) {
        String actual = extractString(dto.samAccountName);
        if (actual == null) return false;
        String actualLower = actual.toLowerCase();
        return actualLower.contains(typedUsername)
            || actualLower.replace('.', '-').equals(typedUsername.replace('.', '-'));
    }

    private Map<String, AdApiUserDto> runQueries(List<String> dniVariants, List<String> nameVariants, String usernameValue) {
        Map<String, AdApiUserDto> merged = new LinkedHashMap<>();
        for (String d : dniVariants) {
            for (String n : nameVariants) {
                for (AdApiUserDto dto : query(baseUrl, apiToken, d, n, usernameValue, httpClient, mapper)) {
                    merged.merge(extractString(dto.samAccountName), dto,
                        (existing, incoming) -> completeness(incoming) > completeness(existing) ? incoming : existing);
                }
            }
        }
        return merged;
    }

    @Override
    public AdCredentialResult validateCredentials(String username, String password) {
        if (!isConfigured()) return new AdCredentialResult(false, List.of());

        if (isMockCredentialValidationEnabled()) {
            return mockValidateCredentials(username);
        }

        try {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            String json = mapper.writeValueAsString(Map.of("username", username, "password", password));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + VALIDATE_CREDENTIALS_PATH))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("AD API validate-credentials failed with status "
                    + response.statusCode() + ": " + response.body());
            }

            JsonNode node = mapper.readTree(response.body());
            boolean valid = node.path("valid").asBoolean(false);
            List<String> groups = new ArrayList<>();
            if (valid && node.has("groups") && node.get("groups").isArray()) {
                node.get("groups").forEach(g -> groups.add(g.asText()));
            }
            return new AdCredentialResult(valid, groups);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("AD API validate-credentials request failed", e);
        }
    }

    private boolean isMockCredentialValidationEnabled() {
        try {
            var adAccess = ConfigService.getInstance().getConfig().adAccess;
            return adAccess != null && adAccess.mockCredentialValidation;
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /**
     * TEMPORARY — see AppConfig.AdAccessConfig.mockCredentialValidation. Stands in for the real
     * AD API's validate-credentials endpoint, which doesn't exist yet: does NOT check the
     * password at all (any value is accepted), but does confirm the username is a real AD
     * account via the already-working search() lookup, so a typo'd/unknown username is still
     * rejected. Prints a loud, unmissable warning every time it's used, since accepting any
     * password is a real (temporary, explicitly accepted) security bypass, not something that
     * should go unnoticed if left on by mistake.
     */
    private AdCredentialResult mockValidateCredentials(String username) {
        System.out.println("[MOCK] AD credential validation is DISABLED (adAccess.mockCredentialValidation=true) "
            + "- password for \"" + username + "\" was NOT checked. Remove this once the real "
            + "validate-credentials endpoint exists on the AD API.");

        // Exact match required — search()'s username matching is a deliberate substring
        // .contains() (needed elsewhere for partial-username recipient lookups), but reusing
        // that here let a login with only a few matching letters of someone else's real
        // username through as "known", with no password check at all. Authentication must
        // resolve to the exact account that was typed, not merely one whose username contains it.
        if (!containsExactUsernameMatch(search(null, null, username), username)) {
            return new AdCredentialResult(false, List.of());
        }

        List<String> groups = new ArrayList<>();
        try {
            String allowedGroup = ConfigService.getInstance().getConfig().adAccess.allowedGroupName;
            if (allowedGroup != null && !allowedGroup.isBlank()) groups.add(allowedGroup.trim());
        } catch (IllegalStateException notLoaded) {
            // no config to read a group name from - leave groups empty, same as an unconfigured gate
        }
        return new AdCredentialResult(true, groups);
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

    /**
     * Non-blank DNI yields [digitsOnly] or [digitsOnly, dotted] when dotting actually changes
     * the value.
     *
     * Investigated whether generating extra dotted guesses anchored to an assumed
     * final length (7 or 8 digits) could recover partial-DNI searches against dotted-stored
     * records — e.g. for the DNI "40858711" (stored dotted as "40.858.711"), trying "40.858.7"
     * as a guess for the 6-digit-typed prefix "408587". That guess is a character-for-character
     * exact prefix of the stored value, yet was confirmed (via direct API calls, bypassing this
     * app entirely) to NOT match — and neither did any other dotted variant tried. The technician
     * separately confirmed the same record fails to prefix-search in the native Windows AD tool
     * too, so this isn't an API bug or something this app's query shape can work around — it's
     * an inherent limitation of how that particular record is indexed in Active Directory itself.
     * Reverted to the simple form below; do not reintroduce assumed-length dot guessing.
     */
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
     * when there are 2+ words, two reordered guesses inserting a comma after the last word
     * (matches "Nombre Apellido" input) and after the first word (matches "Apellido Nombre"
     * input, i.e. the stored order but missing its comma) — we can't tell from the input alone
     * which end holds the surname. All lowercased since the API expects lowercase. Deduplicated
     * since a single-word input, or symmetric input, would otherwise repeat the same query.
     */
    private static List<String> preciseNameVariants(String name) {
        if (isBlank(name)) return singleNullList();
        String asTyped = name.trim().toLowerCase();
        String[] words = asTyped.split("\\s+");
        if (words.length < 2) return List.of(asTyped);

        String rest = String.join(" ", Arrays.copyOfRange(words, 1, words.length));
        String lastWordAsSurname = words[words.length - 1] + ", "
            + String.join(" ", Arrays.copyOfRange(words, 0, words.length - 1));
        String firstWordAsSurname = words[0] + ", " + rest;

        return new ArrayList<>(new LinkedHashSet<>(List.of(asTyped, lastWordAsSurname, firstWordAsSurname)));
    }

    /**
     * Each word of a 2+ word name, queried alone. A combined guess from preciseNameVariants()
     * can never match if an incomplete word sits anywhere but the very end of the query string
     * (see search()'s comment) — querying a word by itself sidesteps that, since it only needs
     * to be a prefix of one stored word, not exactly followed by the rest of the stored string.
     * A single-word input returns empty here since preciseNameVariants() already queries it alone.
     */
    private static List<String> wordFallbackVariants(String name) {
        if (isBlank(name)) return List.of();
        String[] words = name.trim().toLowerCase().split("\\s+");
        if (words.length < 2) return List.of();
        return new ArrayList<>(new LinkedHashSet<>(List.of(words)));
    }

    private static List<String> singleNullList() {
        List<String> l = new ArrayList<>(1);
        l.add(null);
        return l;
    }

    private static boolean containsExactUsernameMatch(List<ADUser> results, String username) {
        return results.stream().anyMatch(u -> u.getUsername().equalsIgnoreCase(username));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static ADUser toADUser(AdApiUserDto dto) {
        return new ADUser(normalizeDni(extractString(dto.dni)), normalizeName(extractString(dto.displayName)),
            extractString(dto.samAccountName), extractString(dto.mail), extractString(dto.ou));
    }

    /**
     * AD returns dni with thousands-separator dots (e.g. "00.000.000"). The name/dni text
     * fields that receive these values have TextFormatters restricting input to digits-only
     * (dni) or letters-and-spaces (name), and TextFormatter rejects programmatic setText()
     * just like typed input — an un-normalized dotted dni or comma-separated name is silently
     * dropped instead of shown, so it must be normalized here at the AD boundary.
     */
    private static String normalizeDni(String dni) {
        return dni == null ? null : dni.replaceAll("[^0-9]", "");
    }

    /** AD returns displayName as "Apellido, Nombre" — see normalizeDni() for why the comma must go. */
    private static String normalizeName(String name) {
        return name == null ? null : name.replace(",", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * Number of non-blank fields on a DTO. Confirmed in practice: a broad/fuzzy query (e.g. an
     * unstructured "name" search matching many people loosely) can return a lighter-weight
     * record for the same person that a more precise query (e.g. the exact "Apellido, Nombre"
     * reordering) returns in full — same samAccountName, missing displayName/dni. When two
     * variant queries both surface the same person, prefer whichever version is more complete
     * instead of arbitrarily keeping whichever query happened to run first.
     */
    private static int completeness(AdApiUserDto dto) {
        int score = 0;
        if (!isBlank(extractString(dto.samAccountName))) score++;
        if (!isBlank(extractString(dto.displayName))) score++;
        if (!isBlank(extractString(dto.dni))) score++;
        if (!isBlank(extractString(dto.mail))) score++;
        if (!isBlank(extractString(dto.ou))) score++;
        return score;
    }

    /**
     * Some AD accounts return one or more fields as a JSON array instead of a plain string
     * (a multi-valued directory attribute) — confirmed in practice on both "dni" and "mail"
     * for the same account, so every field on AdApiUserDto is treated this way rather than
     * assuming only one field can be affected. Uses the first value, or "" if the array is
     * empty. A missing/null field stays null.
     */
    private static String extractString(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) return node.size() > 0 ? node.get(0).asText() : "";
        return node.asText();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AdApiUserDto {
        public JsonNode samAccountName;
        public JsonNode displayName;
        public JsonNode dni;
        public JsonNode mail;
        public JsonNode ou;
    }
}
