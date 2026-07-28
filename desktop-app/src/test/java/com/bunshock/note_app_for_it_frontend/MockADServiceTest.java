package com.bunshock.note_app_for_it_frontend;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.models.AdCredentialResult;
import com.bunshock.note_app_for_it_frontend.services.MockADService;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MockADServiceTest {

    private final MockADService service = new MockADService();

    @Test
    void searchByDni() {
        List<ADUser> results = service.search("35123456", null, null);
        assertEquals(1, results.size());
        assertEquals("Leandro Mantovani", results.get(0).getFullName());
    }

    @Test
    void searchByDniWithDots() {
        List<ADUser> results = service.search("35.123.456", null, null);
        assertEquals(1, results.size());
    }

    @Test
    void searchByName() {
        List<ADUser> results = service.search(null, "leandro", null);
        assertEquals(2, results.size());
    }

    @Test
    void searchByUsername() {
        List<ADUser> results = service.search(null, null, "jperez");
        assertEquals(1, results.size());
        assertEquals("Juan Perez", results.get(0).getFullName());
    }

    @Test
    void noResultsForUnknown() {
        List<ADUser> results = service.search("99999999", null, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void emptyQueryReturnsNothing() {
        List<ADUser> results = service.search("", "", "");
        assertTrue(results.isEmpty());
    }

    @Test
    void validateCredentialsValidForKnownUserWithMockPassword() {
        AdCredentialResult result = service.validateCredentials("jperez", "password123");
        assertTrue(result.isValid());
        assertFalse(result.getGroups().isEmpty());
    }

    @Test
    void validateCredentialsInvalidForWrongPassword() {
        AdCredentialResult result = service.validateCredentials("jperez", "wrong");
        assertFalse(result.isValid());
        assertTrue(result.getGroups().isEmpty());
    }

    @Test
    void validateCredentialsInvalidForUnknownUser() {
        AdCredentialResult result = service.validateCredentials("nobody", "password123");
        assertFalse(result.isValid());
    }
}
