package com.bunshock.note_app_for_it_frontend.services;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import com.bunshock.note_app_for_it_frontend.models.ActionAuditEntry;
import com.bunshock.note_app_for_it_frontend.models.LoginAuditEntry;

class SqliteAuditServiceTest {

    @TempDir
    Path tempDir;

    private SqliteAuditService service;
    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:sqlite:" + tempDir.resolve("audit-test.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE LOGIN_AUDIT (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    username       TEXT NOT NULL,
                    attempted_at   TEXT NOT NULL,
                    success        INTEGER NOT NULL,
                    failure_reason TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE ACTION_AUDIT (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    username     TEXT NOT NULL,
                    event_type   TEXT NOT NULL,
                    occurred_at  TEXT NOT NULL,
                    note_item_id INTEGER,
                    details      TEXT
                )""");
        }
        service = new SqliteAuditService(() -> {
            try { return DriverManager.getConnection(url); }
            catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void logLoginPersistsSuccessfulAttempt() {
        service.logLogin("jperez", true, null);
        List<LoginAuditEntry> logins = service.getAllLogins();
        assertEquals(1, logins.size());
        assertEquals("jperez", logins.get(0).getUsername());
        assertTrue(logins.get(0).isSuccess());
        assertNull(logins.get(0).getFailureReason());
    }

    @Test
    void logLoginPersistsFailedAttemptWithReason() {
        service.logLogin("jperez", false, "INVALID_CREDENTIALS");
        List<LoginAuditEntry> logins = service.getAllLogins();
        assertEquals(1, logins.size());
        assertFalse(logins.get(0).isSuccess());
        assertEquals("INVALID_CREDENTIALS", logins.get(0).getFailureReason());
    }

    @Test
    void getAllLoginsReturnsNewestFirst() {
        service.logLogin("first", true, null);
        service.logLogin("second", true, null);
        List<LoginAuditEntry> logins = service.getAllLogins();
        assertEquals(2, logins.size());
        assertEquals("second", logins.get(0).getUsername());
        assertEquals("first", logins.get(1).getUsername());
    }

    @Test
    void logActionPersistsWithNoteItemId() {
        service.logAction("jperez", "GLPI_SYNC", 42, null);
        List<ActionAuditEntry> actions = service.getAllActions();
        assertEquals(1, actions.size());
        assertEquals("jperez", actions.get(0).getUsername());
        assertEquals("GLPI_SYNC", actions.get(0).getEventType());
        assertEquals(42, actions.get(0).getNoteItemId());
        assertNull(actions.get(0).getDetails());
    }

    @Test
    void logActionPersistsWithNullNoteItemIdForConnectionChanges() {
        service.logAction("jperez", "DB_CONNECTION_CHANGED", null, "Campos modificados: host");
        List<ActionAuditEntry> actions = service.getAllActions();
        assertEquals(1, actions.size());
        assertNull(actions.get(0).getNoteItemId());
        assertEquals("Campos modificados: host", actions.get(0).getDetails());
    }

    @Test
    void getAllActionsReturnsNewestFirst() {
        service.logAction("jperez", "GLPI_SYNC", 1, null);
        service.logAction("jperez", "GLPI_REJECT", 2, "motivo");
        List<ActionAuditEntry> actions = service.getAllActions();
        assertEquals(2, actions.size());
        assertEquals("GLPI_REJECT", actions.get(0).getEventType());
        assertEquals("GLPI_SYNC", actions.get(1).getEventType());
    }
}
