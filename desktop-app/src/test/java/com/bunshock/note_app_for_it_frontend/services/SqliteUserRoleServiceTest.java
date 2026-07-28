package com.bunshock.note_app_for_it_frontend.services;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

// IUserRoleService is read-only by design — an admin sets a role by running SQL directly
// against USER_ROLE, not through any app code (see IUserRoleService's own doc). This test seeds
// rows with plain INSERT statements, the same way a real admin would, rather than calling a
// setRole()-style method that no longer exists.
class SqliteUserRoleServiceTest {

    @TempDir
    Path tempDir;

    private SqliteUserRoleService service;
    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:sqlite:" + tempDir.resolve("user-role-test.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE USER_ROLE (
                    username TEXT PRIMARY KEY,
                    role     TEXT NOT NULL
                )""");
        }
        service = new SqliteUserRoleService(() -> {
            try { return DriverManager.getConnection(url); }
            catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    private void insertRole(String username, String role) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("INSERT INTO USER_ROLE (username, role) VALUES (?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, role);
            ps.executeUpdate();
        }
    }

    @Test
    void getRoleDefaultsToUserWhenNoRowExists() {
        assertEquals(IUserRoleService.ROLE_USER, service.getRole("nobody"));
    }

    @Test
    void getRoleReturnsWhatWasManuallyInsertedViaSql() throws SQLException {
        insertRole("jperez", IUserRoleService.ROLE_ADMIN);
        assertEquals(IUserRoleService.ROLE_ADMIN, service.getRole("jperez"));
    }

    @Test
    void getRoleIsScopedToTheExactUsername() throws SQLException {
        insertRole("jperez", IUserRoleService.ROLE_ADMIN);
        assertEquals(IUserRoleService.ROLE_USER, service.getRole("otheruser"));
    }
}
