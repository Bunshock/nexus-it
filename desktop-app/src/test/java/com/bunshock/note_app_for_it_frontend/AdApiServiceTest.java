package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Method;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
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
    private List<String> nameVariants(String name) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("nameVariants", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, name);
    }

    private String dotFormat(String digits) throws Exception {
        Method m = AdApiService.class.getDeclaredMethod("dotFormat", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, digits);
    }

    @Test
    void dotFormatGroupsByThreeFromTheRight() throws Exception {
        assertEquals("45.933.368", dotFormat("45933368"));
        assertEquals("1.111.111", dotFormat("1111111"));
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
    void nameVariantsReordersLastWordAsSurname() throws Exception {
        List<String> variants = nameVariants("Joaquín Rodriguez");
        assertEquals(2, variants.size());
        assertTrue(variants.contains("joaquín rodriguez"));
        assertTrue(variants.contains("rodriguez, joaquín"));
    }

    @Test
    void nameVariantsIsSingleValueForOneWord() throws Exception {
        List<String> variants = nameVariants("Rodriguez");
        assertEquals(1, variants.size());
        assertEquals("rodriguez", variants.get(0));
    }

    @Test
    void nameVariantsIsSingleNullForBlankInput() throws Exception {
        List<String> variants = nameVariants(null);
        assertEquals(1, variants.size());
        assertNull(variants.get(0));
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
}
