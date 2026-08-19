package com.bunshock.note_app_for_it_frontend.services;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import com.bunshock.note_app_for_it_frontend.models.Permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

// IUserRoleService is read-only by design — a superadmin sets a role/sede/permission by running
// SQL directly against APP_USER/ROLE_PERMISSION, not through any app code (see IUserRoleService's
// own doc). This test seeds rows with plain INSERT statements, the same way a real superadmin
// would, rather than calling a setRole()-style method that doesn't exist on the real service.
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
                CREATE TABLE ROLE (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""");
            stmt.executeUpdate("INSERT INTO ROLE (name) VALUES ('USER'), ('ADMIN'), ('SUPERADMIN')");
            stmt.executeUpdate("""
                CREATE TABLE APP_USER (
                    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                    username           TEXT NOT NULL UNIQUE,
                    role_id            INTEGER NOT NULL REFERENCES ROLE(id),
                    sede_id            INTEGER,
                    bypass_group_check INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE ROLE_PERMISSION (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    role_id    INTEGER NOT NULL REFERENCES ROLE(id),
                    permission TEXT NOT NULL,
                    UNIQUE (role_id, permission)
                )""");
        }
        service = new SqliteUserRoleService(() -> {
            try { return DriverManager.getConnection(url); }
            catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    private void insertUser(String username, String role, Integer sedeId) throws SQLException {
        insertUser(username, role, sedeId, false);
    }

    private void insertUser(String username, String role, Integer sedeId, boolean bypassGroupCheck) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO APP_USER (username, role_id, sede_id, bypass_group_check)
                 VALUES (?, (SELECT id FROM ROLE WHERE name = ?), ?, ?)
                 """)) {
            ps.setString(1, username);
            ps.setString(2, role);
            if (sedeId == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setInt(3, sedeId);
            ps.setInt(4, bypassGroupCheck ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void insertPermission(String role, Permission permission) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO ROLE_PERMISSION (role_id, permission)
                 VALUES ((SELECT id FROM ROLE WHERE name = ?), ?)
                 """)) {
            ps.setString(1, role);
            ps.setString(2, permission.name());
            ps.executeUpdate();
        }
    }

    @Test
    void getRoleDefaultsToUserWhenNoRowExists() {
        assertEquals(IUserRoleService.ROLE_USER, service.getRole("nobody"));
    }

    @Test
    void getRoleReturnsWhatWasManuallyInsertedViaSql() throws SQLException {
        insertUser("jperez", IUserRoleService.ROLE_ADMIN, null);
        assertEquals(IUserRoleService.ROLE_ADMIN, service.getRole("jperez"));
    }

    @Test
    void getRoleIsScopedToTheExactUsername() throws SQLException {
        insertUser("jperez", IUserRoleService.ROLE_ADMIN, null);
        assertEquals(IUserRoleService.ROLE_USER, service.getRole("otheruser"));
    }

    @Test
    void getSedeIdNullWhenNoRowExists() {
        assertNull(service.getSedeId("nobody"));
    }

    @Test
    void getSedeIdNullWhenRowExistsWithNoSedeAssigned() throws SQLException {
        insertUser("jperez", IUserRoleService.ROLE_USER, null);
        assertNull(service.getSedeId("jperez"));
    }

    @Test
    void getSedeIdReturnsWhatWasManuallyAssignedViaSql() throws SQLException {
        insertUser("jperez", IUserRoleService.ROLE_ADMIN, 7);
        assertEquals(7, service.getSedeId("jperez"));
    }

    @Test
    void getPermissionsForRoleEmptyWhenNoRowsExist() {
        assertTrue(service.getPermissionsForRole(IUserRoleService.ROLE_ADMIN).isEmpty());
    }

    @Test
    void getPermissionsForRoleReturnsWhatWasManuallyGrantedViaSql() throws SQLException {
        insertPermission(IUserRoleService.ROLE_ADMIN, Permission.MANAGE_TYPES);
        insertPermission(IUserRoleService.ROLE_ADMIN, Permission.MANAGE_BRANDS);
        insertPermission(IUserRoleService.ROLE_SUPERADMIN, Permission.EDIT_SMTP_CONFIG);

        var adminPermissions = service.getPermissionsForRole(IUserRoleService.ROLE_ADMIN);
        assertEquals(2, adminPermissions.size());
        assertTrue(adminPermissions.contains(Permission.MANAGE_TYPES));
        assertTrue(adminPermissions.contains(Permission.MANAGE_BRANDS));
        assertFalse(adminPermissions.contains(Permission.EDIT_SMTP_CONFIG));

        assertEquals(java.util.Set.of(Permission.EDIT_SMTP_CONFIG),
            service.getPermissionsForRole(IUserRoleService.ROLE_SUPERADMIN));
    }

    @Test
    void getPermissionsForRoleIgnoresStrayUnknownPermissionString() throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO ROLE_PERMISSION (role_id, permission)
                 VALUES ((SELECT id FROM ROLE WHERE name = ?), ?)
                 """)) {
            ps.setString(1, IUserRoleService.ROLE_ADMIN);
            ps.setString(2, "NOT_A_REAL_PERMISSION");
            ps.executeUpdate();
        }
        insertPermission(IUserRoleService.ROLE_ADMIN, Permission.MANAGE_TYPES);

        assertEquals(java.util.Set.of(Permission.MANAGE_TYPES),
            service.getPermissionsForRole(IUserRoleService.ROLE_ADMIN));
    }

    @Test
    void hasGroupCheckBypassFalseWhenNoRowExists() {
        assertFalse(service.hasGroupCheckBypass("nobody"));
    }

    @Test
    void hasGroupCheckBypassFalseWhenRowExistsWithFlagUnset() throws SQLException {
        insertUser("intern1", IUserRoleService.ROLE_USER, null);
        assertFalse(service.hasGroupCheckBypass("intern1"));
    }

    @Test
    void hasGroupCheckBypassTrueWhenExplicitlySetViaSql() throws SQLException {
        insertUser("intern1", IUserRoleService.ROLE_USER, null, true);
        assertTrue(service.hasGroupCheckBypass("intern1"));
    }
}
