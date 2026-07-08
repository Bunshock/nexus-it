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
}
