package com.bunshock.note_app_for_it_frontend;

import com.bunshock.note_app_for_it_frontend.services.RemoteDatabaseService;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteDatabaseServiceTest {

    private final RemoteDatabaseService service = RemoteDatabaseService.getInstance();

    @Test
    void notConfiguredWhenHostIsNull() {
        service.configure(null, 5432, "db", "user", "pass");
        assertFalse(service.isConfigured());
    }

    @Test
    void notConfiguredWhenHostIsBlank() {
        service.configure("   ", 5432, "db", "user", "pass");
        assertFalse(service.isConfigured());
    }

    @Test
    void configuredWhenHostIsSet() {
        service.configure("localhost", 5432, "db", "user", "pass");
        assertTrue(service.isConfigured());
    }

    @Test
    void testConnectionFalseWhenNotConfigured() {
        service.configure(null, 5432, "db", "user", "pass");
        assertFalse(service.testConnection());
    }

    @Test
    void testConnectionFalseForUnreachableHost() {
        service.configure("localhost", 1, "db", "user", "pass");
        assertFalse(service.testConnection());
    }

    @Test
    void getConnectionThrowsWhenNotConfigured() {
        service.configure(null, 5432, "db", "user", "pass");
        assertThrows(IllegalStateException.class, service::getConnection);
    }
}
