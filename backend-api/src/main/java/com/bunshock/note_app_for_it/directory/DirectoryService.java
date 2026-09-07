package com.bunshock.note_app_for_it.directory;

import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.directory.AdApiClient.AdApiUser;
import com.bunshock.note_app_for_it.directory.dto.DirectoryUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves directory accounts for {@code GET /api/v1/directory/users}. Ported from the desktop
 * app's {@code AdApiService.search()} — the raw directory API does loose substring matching and
 * does NOT reliably AND its {@code dni}/{@code name}/{@code username} params, so this:
 *
 * <ol>
 *   <li>fans the query out into digit/dotted DNI variants and comma-reordered name variants
 *       ("Nombre Apellido" typed vs. "Apellido, Nombre" stored — either end could be the surname),
 *   <li>merges the results by {@code samAccountName}, preferring the more complete record when a
 *       person shows up under more than one variant,
 *   <li>runs a per-word fallback round only when the precise variants found nothing,
 *   <li>then re-verifies <em>every</em> supplied field against each candidate client-side —
 *       any candidate that fails even one supplied field is dropped, so the result is a true AND.
 * </ol>
 *
 * The compensating logic doesn't move just because it's server-side now — the underlying
 * directory API is unchanged.
 */
@Service
public class DirectoryService {

    private final AdApiClient client;
    private final DirectoryProperties properties;

    public DirectoryService(AdApiClient client, DirectoryProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public List<DirectoryUser> search(String dni, String name, String username) {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DIRECTORY_NOT_CONFIGURED",
                    "El directorio no está configurado todavía.");
        }
        if (isBlank(dni) && isBlank(name) && isBlank(username)) {
            return List.of();
        }

        List<String> dniVariants = dniVariants(dni);
        String usernameValue = isBlank(username) ? null : username.trim().toLowerCase();

        Map<String, AdApiUser> merged = runQueries(dniVariants, preciseNameVariants(name), usernameValue);

        // A word that's incomplete anywhere but the very end of a combined guess breaks the
        // server's substring match; querying that word alone can still match it. Only pay for the
        // extra round when the precise guesses found nothing.
        if (merged.isEmpty() && !isBlank(name)) {
            List<String> fallback = wordFallbackVariants(name);
            if (!fallback.isEmpty()) {
                merged.putAll(runQueries(dniVariants, fallback, usernameValue));
            }
        }

        // Re-verify EVERY supplied field against each candidate, unconditionally — the server
        // isn't trusted to have ANDed them. A candidate failing even one supplied field is out.
        List<String> nameWords = isBlank(name) ? List.of() : List.of(name.trim().toLowerCase().split("\\s+"));
        String dniDigits = isBlank(dni) ? null : dni.replaceAll("[^0-9]", "");
        merged.values().removeIf(dto ->
                (!nameWords.isEmpty() && !matchesAllWords(dto, nameWords))
                        || (dniDigits != null && !dniDigits.isEmpty() && !matchesDni(dto, dniDigits))
                        || (usernameValue != null && !matchesUsername(dto, usernameValue)));

        return merged.values().stream().map(DirectoryService::toDirectoryUser).toList();
    }

    private Map<String, AdApiUser> runQueries(List<String> dniVariants, List<String> nameVariants, String usernameValue) {
        Map<String, AdApiUser> merged = new LinkedHashMap<>();
        for (String d : dniVariants) {
            for (String n : nameVariants) {
                for (AdApiUser dto : client.fetch(d, n, usernameValue)) {
                    merged.merge(dto.samAccountName(), dto,
                            (existing, incoming) -> completeness(incoming) > completeness(existing) ? incoming : existing);
                }
            }
        }
        return merged;
    }

    // ── candidate re-verification ────────────────────────────────────────────

    static boolean matchesAllWords(AdApiUser dto, List<String> words) {
        String haystack = dto.displayName() == null ? "" : dto.displayName().toLowerCase();
        return words.stream().allMatch(haystack::contains);
    }

    static boolean matchesDni(AdApiUser dto, String typedDigits) {
        String actual = dto.dni();
        if (actual == null) {
            return false;
        }
        return actual.replaceAll("[^0-9]", "").startsWith(typedDigits);
    }

    /** Separator-agnostic, so a typed "juan.perez" also matches a stored "juan-perez". */
    static boolean matchesUsername(AdApiUser dto, String typedUsername) {
        String actual = dto.samAccountName();
        if (actual == null) {
            return false;
        }
        String actualLower = actual.toLowerCase();
        return actualLower.contains(typedUsername)
                || actualLower.replace('.', '-').equals(typedUsername.replace('.', '-'));
    }

    static int completeness(AdApiUser dto) {
        int score = 0;
        if (!isBlank(dto.samAccountName())) score++;
        if (!isBlank(dto.displayName())) score++;
        if (!isBlank(dto.dni())) score++;
        if (!isBlank(dto.mail())) score++;
        if (!isBlank(dto.ou())) score++;
        return score;
    }

    // ── query-variant builders ──────────────────────────────────────────────

    /** {@code [digits]} or {@code [digits, dotted]} when grouping by 3 actually changes it. */
    static List<String> dniVariants(String dni) {
        if (isBlank(dni)) {
            return singleNullList();
        }
        String digits = dni.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return singleNullList();
        }
        String dotted = dotFormat(digits);
        return dotted.equals(digits) ? List.of(digits) : List.of(digits, dotted);
    }

    /** Groups digits by 3 from the right, e.g. "45933368" -> "45.933.368". */
    static String dotFormat(String digits) {
        StringBuilder sb = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) {
                sb.append('.');
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    /**
     * The name as typed, plus (for 2+ words) two comma-reordered guesses — one treating the last
     * word as the surname ("Nombre Apellido" input), one the first ("Apellido Nombre" input,
     * i.e. stored order missing its comma). Lowercased; deduplicated.
     */
    static List<String> preciseNameVariants(String name) {
        if (isBlank(name)) {
            return singleNullList();
        }
        String asTyped = name.trim().toLowerCase();
        String[] words = asTyped.split("\\s+");
        if (words.length < 2) {
            return List.of(asTyped);
        }
        String rest = String.join(" ", Arrays.copyOfRange(words, 1, words.length));
        String lastWordAsSurname = words[words.length - 1] + ", "
                + String.join(" ", Arrays.copyOfRange(words, 0, words.length - 1));
        String firstWordAsSurname = words[0] + ", " + rest;
        return new ArrayList<>(new LinkedHashSet<>(List.of(asTyped, lastWordAsSurname, firstWordAsSurname)));
    }

    /** Each word of a 2+ word name, queried alone. Empty for a single-word name. */
    static List<String> wordFallbackVariants(String name) {
        if (isBlank(name)) {
            return List.of();
        }
        String[] words = name.trim().toLowerCase().split("\\s+");
        if (words.length < 2) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(List.of(words)));
    }

    // ── normalization at the directory boundary ─────────────────────────────

    private static DirectoryUser toDirectoryUser(AdApiUser dto) {
        return new DirectoryUser(dto.samAccountName(), normalizeName(dto.displayName()),
                dto.mail(), normalizeDni(dto.dni()), dto.ou());
    }

    /** Directory returns dni with thousands-separator dots — strip to digits. */
    static String normalizeDni(String dni) {
        return dni == null ? null : dni.replaceAll("[^0-9]", "");
    }

    /** Directory returns displayName as "Apellido, Nombre" — drop the comma, collapse spaces. */
    static String normalizeName(String name) {
        return name == null ? null : name.replace(",", "").replaceAll("\\s+", " ").trim();
    }

    private static List<String> singleNullList() {
        List<String> l = new ArrayList<>(1);
        l.add(null);
        return l;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
