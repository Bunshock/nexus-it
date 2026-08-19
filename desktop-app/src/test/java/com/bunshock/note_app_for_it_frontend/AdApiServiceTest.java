package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Method;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.services.AdApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdApiServiceTest {

    private final AdApiService service = AdApiService.getInstance();
    private final ObjectMapper mapper = new ObjectMapper();

    private String extractString(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        Method m = AdApiService.class.getDeclaredMethod("extractString", JsonNode.class);
        m.setAccessible(true);
        return (String) m.invoke(null, node);
    }

    private Object buildDto(String samAccountName, String displayName, String dni, String mail, String ou) throws Exception {
        Class<?> dtoClass = Class.forName(
            "com.bunshock.note_app_for_it_frontend.services.AdApiService$AdApiUserDto");
        var constructor = dtoClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object dto = constructor.newInstance();
        setField(dtoClass, dto, "samAccountName", samAccountName);
        setField(dtoClass, dto, "displayName", displayName);
        setField(dtoClass, dto, "dni", dni);
        setField(dtoClass, dto, "mail", mail);
        setField(dtoClass, dto, "ou", ou);
        return dto;
    }

    private void setField(Class<?> dtoClass, Object dto, String fieldName, String json) throws Exception {
        var field = dtoClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(dto, mapper.readTree(json));
    }

    private int completeness(Object dto) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("completeness", dto.getClass());
        m.setAccessible(true);
        return (int) m.invoke(null, dto);
    }

    private ADUser toADUser(Object dto) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("toADUser", dto.getClass());
        m.setAccessible(true);
        return (ADUser) m.invoke(null, dto);
    }

    @SuppressWarnings("unchecked")
    private List<String> dniVariants(String dni) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("dniVariants", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, dni);
    }

    @SuppressWarnings("unchecked")
    private List<String> preciseNameVariants(String name) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("preciseNameVariants", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, name);
    }

    @SuppressWarnings("unchecked")
    private List<String> wordFallbackVariants(String name) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("wordFallbackVariants", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, name);
    }

    private boolean matchesAllWords(Object dto, List<String> words) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("matchesAllWords", dto.getClass(), List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, dto, words);
    }

    private boolean matchesDni(Object dto, String typedDigits) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("matchesDni", dto.getClass(), String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, dto, typedDigits);
    }

    private boolean matchesUsername(Object dto, String typedUsername) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("matchesUsername", dto.getClass(), String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, dto, typedUsername);
    }

    private String dotFormat(String digits) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("dotFormat", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, digits);
    }

    @SuppressWarnings("unchecked")
    private boolean containsExactUsernameMatch(List<ADUser> results, String username) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("containsExactUsernameMatch", List.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, results, username);
    }

    @Test
    void dotFormatGroupsByThreeFromTheRight() throws Exception {
        assertEquals("45.933.368", dotFormat("45933368"));
        assertEquals("1.111.111", dotFormat("1111111"));
    }

    // Guards the fix for a real login bug: mockValidateCredentials() used to accept "known user"
    // whenever search() returned anything at all — since search()'s own username matching is a
    // deliberate substring .contains() (needed for partial-username recipient lookups elsewhere),
    // typing just a few letters of a real employee's username was enough to authenticate as them,
    // with no password check. containsExactUsernameMatch() is the fix: only an exact match counts.
    @Test
    void containsExactUsernameMatchRejectsSubstringOnlyResult() throws Exception {
        ADUser lgarcia = new ADUser("38987654", "Leandro Garcia", "lgarcia",
            "lgarcia@ues21.edu.ar", "OU=BuenosAires,OU=Docentes,DC=ues21");
        assertFalse(containsExactUsernameMatch(List.of(lgarcia), "garcia"),
            "a substring match must not count as a known user for authentication");
        assertFalse(containsExactUsernameMatch(List.of(lgarcia), "gar"));
    }

    @Test
    void containsExactUsernameMatchAcceptsExactMatchCaseInsensitive() throws Exception {
        ADUser lgarcia = new ADUser("38987654", "Leandro Garcia", "lgarcia",
            "lgarcia@ues21.edu.ar", "OU=BuenosAires,OU=Docentes,DC=ues21");
        assertTrue(containsExactUsernameMatch(List.of(lgarcia), "lgarcia"));
        assertTrue(containsExactUsernameMatch(List.of(lgarcia), "LGarcia"));
    }

    @Test
    void containsExactUsernameMatchFalseForEmptyResults() throws Exception {
        assertFalse(containsExactUsernameMatch(List.of(), "anything"));
    }

    /**
     * Documents a confirmed, accepted limitation — not a bug to "fix" here. Two
     * real AD users share the same first 5 DNI digits, one stored undotted ("40858705"), one
     * stored dotted ("40.858.711"). A partial-DNI search for the dotted user only succeeds when
     * the typed prefix happens to land on a dot boundary (5 digits) or is the full exact value
     * (8 digits) — 6 and 7 digit prefixes genuinely don't find him, confirmed via direct calls
     * to the real API (bypassing this app) and independently reproduced in the native Windows
     * AD search tool, so it's how that record is indexed in AD itself, not something any query
     * shape from this app can work around. See dniVariants()'s doc for the full investigation.
     */
    @Test
    void dniVariantsRealAdApiScenario() throws Exception {
        String mariaRaw = "40858705";
        String leandroRaw = "40.858.711";

        List<String> q1 = dniVariants("40858");
        assertTrue(anyVariantMatches(q1, mariaRaw), "5-digit prefix matches Maria");
        assertTrue(anyVariantMatches(q1, leandroRaw), "5-digit prefix happens to land on a dot boundary, matches Leandro too");

        List<String> q2 = dniVariants("408587");
        assertTrue(anyVariantMatches(q2, mariaRaw), "6-digit prefix matches Maria");
        assertFalse(anyVariantMatches(q2, leandroRaw), "6-digit prefix does NOT match Leandro — accepted AD limitation");

        List<String> q4 = dniVariants("40858711");
        assertFalse(anyVariantMatches(q4, mariaRaw), "full 8-digit query is Leandro's dni, not Maria's");
        assertTrue(anyVariantMatches(q4, leandroRaw), "full exact 8-digit query matches Leandro");
    }

    /** Simulates the real AD API's dni prefix match: a candidate matches a search if the
     * candidate's own raw stored dni string starts with ANY generated variant. */
    private boolean anyVariantMatches(List<String> variants, String rawStoredDni) {
        return variants.stream().anyMatch(rawStoredDni::startsWith);
    }

    @Test
    void dniVariantsIncludesBothFormsWhenDottingChangesValue() throws Exception {
        List<String> variants = dniVariants("45933368");
        assertEquals(2, variants.size());
        assertTrue(variants.contains("45933368"));
        assertTrue(variants.contains("45.933.368"));
    }

    @Test
    void dniVariantsStripsExistingDotsBeforeVarying() throws Exception {
        List<String> variants = dniVariants("45.933.368");
        assertTrue(variants.contains("45933368"));
        assertTrue(variants.contains("45.933.368"));
    }

    @Test
    void dniVariantsIsSingleNullForBlankInput() throws Exception {
        List<String> variants = dniVariants(null);
        assertEquals(1, variants.size());
        assertNull(variants.get(0));
    }

    @Test
    void preciseNameVariantsCoversBothSurnamePositions() throws Exception {
        List<String> variants = preciseNameVariants("Joaquín Rodriguez");
        assertEquals(3, variants.size());
        assertTrue(variants.contains("joaquín rodriguez"));
        assertTrue(variants.contains("rodriguez, joaquín"));
        assertTrue(variants.contains("joaquín, rodriguez"));
    }

    @Test
    void preciseNameVariantsIsSingleValueForOneWord() throws Exception {
        List<String> variants = preciseNameVariants("Rodriguez");
        assertEquals(1, variants.size());
        assertEquals("rodriguez", variants.get(0));
    }

    @Test
    void preciseNameVariantsIsSingleNullForBlankInput() throws Exception {
        List<String> variants = preciseNameVariants(null);
        assertEquals(1, variants.size());
        assertNull(variants.get(0));
    }

    @Test
    void preciseNameVariantsDedupesWhenGuessesCoincide() throws Exception {
        // A 2-word input where both surname-position guesses would produce the same string
        // (repeating the same word twice) should not yield duplicate query variants.
        List<String> variants = preciseNameVariants("Rodriguez Rodriguez");
        assertEquals(2, variants.size());
    }

    @Test
    void wordFallbackVariantsReturnsEachWordAlone() throws Exception {
        List<String> variants = wordFallbackVariants("Joaquin Rodrig");
        assertEquals(2, variants.size());
        assertTrue(variants.contains("joaquin"));
        assertTrue(variants.contains("rodrig"));
    }

    @Test
    void wordFallbackVariantsEmptyForSingleWord() throws Exception {
        assertTrue(wordFallbackVariants("Rodriguez").isEmpty());
    }

    @Test
    void wordFallbackVariantsEmptyForBlankInput() throws Exception {
        assertTrue(wordFallbackVariants(null).isEmpty());
        assertTrue(wordFallbackVariants("").isEmpty());
    }

    @Test
    void notConfiguredWhenUrlOrTokenMissing() {
        service.configure(null, null);
        assertFalse(service.isConfigured());
        service.configure("http://ad-api", null);
        assertFalse(service.isConfigured());
        service.configure(null, "token");
        assertFalse(service.isConfigured());
    }

    @Test
    void configuredWhenBothUrlAndTokenPresent() {
        service.configure("http://ad-api", "token");
        assertTrue(service.isConfigured());
    }

    @Test
    void searchReturnsEmptyWhenNotConfigured() {
        service.configure(null, null);
        List<ADUser> results = service.search("12345678", "name", "user");
        assertTrue(results.isEmpty());
    }

    @Test
    void searchReturnsEmptyWhenAllCriteriaBlankEvenIfConfigured() {
        service.configure("http://ad-api", "token");
        List<ADUser> results = service.search("", "", "");
        assertTrue(results.isEmpty());
    }

    @Test
    void testConnectionFalseForBlankUrlOrToken() {
        assertFalse(service.testConnection(null, "token", "user"));
        assertFalse(service.testConnection("http://ad-api", null, "user"));
    }

    @Test
    void completenessCountsNonBlankFields() throws Exception {
        Object full = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertEquals(5, completeness(full));

        Object partial = buildDto("\"jrodriguez\"", "null", "null",
            "\"jrodriguez@ues21.edu.ar\"", "null");
        assertEquals(2, completeness(partial));

        Object empty = buildDto("null", "null", "null", "null", "null");
        assertEquals(0, completeness(empty));
    }

    @Test
    void completenessTreatsEmptyArrayAsBlank() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "[]", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "null");
        // displayName is an empty array -> extractString returns "" -> blank -> not counted
        assertEquals(3, completeness(dto));
    }

    @Test
    void extractStringReadsPlainStringValue() throws Exception {
        assertEquals("45933368", extractString("\"45933368\""));
    }

    @Test
    void extractStringUsesFirstElementWhenArray() throws Exception {
        assertEquals("45933368", extractString("[\"45933368\", \"00000000\"]"));
        assertEquals("joaquin.rodriguez@ues21.edu.ar",
            extractString("[\"joaquin.rodriguez@ues21.edu.ar\", \"jrodriguez@ues21.edu.ar\"]"));
    }

    @Test
    void extractStringIsEmptyStringForEmptyArray() throws Exception {
        assertEquals("", extractString("[]"));
    }

    @Test
    void extractStringIsNullWhenFieldIsJsonNull() throws Exception {
        assertNull(extractString("null"));
    }

    @Test
    void toADUserStripsDotsFromDni() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "\"00.000.000\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertEquals("00000000", toADUser(dto).getDni());
    }

    @Test
    void toADUserStripsCommaFromDisplayName() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertEquals("Rodriguez Joaquin", toADUser(dto).getFullName());
    }

    @Test
    void toADUserLeavesAlreadyCleanValuesUnchanged() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Joaquin Rodriguez\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        ADUser user = toADUser(dto);
        assertEquals("Joaquin Rodriguez", user.getFullName());
        assertEquals("45933368", user.getDni());
    }

    @Test
    void toADUserHandlesNullDniAndDisplayName() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "null", "null",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        ADUser user = toADUser(dto);
        assertNull(user.getFullName());
        assertNull(user.getDni());
    }

    @Test
    void matchesAllWordsTrueWhenDisplayNameContainsEveryWord() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertTrue(matchesAllWords(dto, List.of("joaquin", "rodrig")));
    }

    @Test
    void matchesAllWordsFalseWhenDisplayNameMissingAWord() throws Exception {
        // "Joaquin Rodrig" falls back to querying "joaquin" and "rodrig" separately — the raw
        // union would include every "Joaquin" and every "Rodrig*" surname; this check narrows
        // it back down to only candidates whose displayName actually contains both words.
        Object joaquinGomez = buildDto("\"jgomez\"", "\"Gomez, Joaquin\"", "\"11111111\"",
            "\"jgomez@ues21.edu.ar\"", "\"OU=IT\"");
        assertFalse(matchesAllWords(joaquinGomez, List.of("joaquin", "rodrig")));

        Object anaRodriguez = buildDto("\"arodriguez\"", "\"Rodriguez, Ana\"", "\"22222222\"",
            "\"arodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertFalse(matchesAllWords(anaRodriguez, List.of("joaquin", "rodrig")));
    }

    @Test
    void matchesAllWordsIsCaseInsensitive() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"RODRIGUEZ, JOAQUIN\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertTrue(matchesAllWords(dto, List.of("joaquin", "rodrig")));
    }

    @Test
    void matchesAllWordsFalseWhenNoDisplayName() throws Exception {
        Object dto = buildDto("\"jgomez\"", "null", "\"11111111\"",
            "\"jgomez@ues21.edu.ar\"", "\"OU=IT\"");
        assertFalse(matchesAllWords(dto, List.of("joaquin")));
    }

    // ── matchesDni / matchesUsername (AND-enforcement fix) ──────────

    @Test
    void matchesDniTrueWhenActualStartsWithTyped() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertTrue(matchesDni(dto, "459333"));
        assertTrue(matchesDni(dto, "45933368"));
    }

    @Test
    void matchesDniFalseWhenActualBelongsToSomeoneElse() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        // Reproduces the reported bug: a name match whose DNI doesn't actually start with the
        // typed (wrong/invalid) DNI must be excluded, not silently kept.
        assertFalse(matchesDni(dto, "99999999"));
    }

    @Test
    void matchesDniIgnoresDotsOnBothSides() throws Exception {
        Object dto = buildDto("\"lmantovani\"", "\"Mantovani, Leandro\"", "\"40.858.711\"",
            "\"leandro.mantovani@ues21.edu.ar\"", "\"OU=IT\"");
        assertTrue(matchesDni(dto, "40858711"));
        assertTrue(matchesDni(dto, "408587"));
    }

    @Test
    void matchesDniFalseWhenDtoHasNoDni() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "null",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertFalse(matchesDni(dto, "45933368"));
    }

    @Test
    void matchesUsernameTrueWhenActualContainsTyped() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertTrue(matchesUsername(dto, "jrodriguez"));
        assertTrue(matchesUsername(dto, "rodri"));
    }

    @Test
    void matchesUsernameFalseWhenUnrelated() throws Exception {
        Object dto = buildDto("\"jrodriguez\"", "\"Rodriguez, Joaquin\"", "\"45933368\"",
            "\"jrodriguez@ues21.edu.ar\"", "\"OU=IT\"");
        assertFalse(matchesUsername(dto, "adiaz"));
    }

    @Test
    void matchesUsernameIsSeparatorAgnostic() throws Exception {
        Object dto = buildDto("\"juan.perez\"", "\"Perez, Juan\"", "\"45933368\"",
            "\"juan.perez@ues21.edu.ar\"", "\"OU=IT\"");
        assertTrue(matchesUsername(dto, "juan-perez"));
    }
}
