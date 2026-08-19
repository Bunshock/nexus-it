package com.bunshock.note_app_for_it_frontend;

import com.bunshock.note_app_for_it_frontend.services.core.RemoteDatabaseService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteDatabaseServiceTest {

    private final RemoteDatabaseService service = RemoteDatabaseService.getInstance();

    @Test
    void notConfiguredWhenHostIsNull() {
        service.configure(null, 1433, "db", "user", "pass");
        assertFalse(service.isConfigured());
    }

    @Test
    void notConfiguredWhenHostIsBlank() {
        service.configure("   ", 1433, "db", "user", "pass");
        assertFalse(service.isConfigured());
    }

    @Test
    void configuredWhenHostIsSet() {
        service.configure("localhost", 1433, "db", "user", "pass");
        assertTrue(service.isConfigured());
    }

    @Test
    void testConnectionFalseWhenNotConfigured() {
        service.configure(null, 1433, "db", "user", "pass");
        assertFalse(service.testConnection());
    }

    @Test
    void testConnectionFalseForUnreachableHost() {
        service.configure("localhost", 1, "db", "user", "pass");
        assertFalse(service.testConnection());
    }

    @Test
    void getConnectionThrowsWhenNotConfigured() {
        service.configure(null, 1433, "db", "user", "pass");
        assertThrows(IllegalStateException.class, service::getConnection);
    }

    @Test
    void testConnectionOverloadFalseForNullHost() {
        assertFalse(service.testConnection(null, 1433, "db", "user", "pass"));
    }

    @Test
    void testConnectionOverloadFalseForBlankHost() {
        assertFalse(service.testConnection("   ", 1433, "db", "user", "pass"));
    }

    @Test
    void testConnectionOverloadFalseForUnreachableHost() {
        assertFalse(service.testConnection("localhost", 1, "db", "user", "pass"));
    }
}
