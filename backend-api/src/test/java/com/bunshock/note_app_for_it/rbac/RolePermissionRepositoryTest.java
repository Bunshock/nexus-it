package com.bunshock.note_app_for_it.rbac;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JDBC test against embedded H2 (role-permission-test-schema.sql — H2-native stand-in,
 * same precedent as the other *RepositoryTest classes). {@link RolePermissionRepository}
 * drives every permission check + the new {@code GET /roles/{role}/permissions}; it had no
 * direct DB coverage before (only mocked in {@link PermissionGuardTest}).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@JdbcTest
@Sql("/role-permission-test-schema.sql")
class RolePermissionRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private RolePermissionRepository repository;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ROLE_PERMISSION");
        repository = new RolePermissionRepository(jdbc);
    }

    private void grant(String role, String permission) {
        jdbc.update("INSERT INTO ROLE_PERMISSION (role_id, permission) "
                + "SELECT id, ? FROM ROLE WHERE name = ?", permission, role);
    }

    @Test
    void returnsExactlyTheGrantedPermissionsForARole() {
        grant("ADMIN", "APPROVE_NOTES");
        grant("ADMIN", "MANAGE_STOCK");
        grant("USER", "VALIDATE_RETURNS");

        assertEquals(Set.of(Permission.APPROVE_NOTES, Permission.MANAGE_STOCK),
                repository.getPermissionsForRole("ADMIN"));
        assertEquals(Set.of(Permission.VALIDATE_RETURNS),
                repository.getPermissionsForRole("USER"));
    }

    @Test
    void returnsAnEmptySetForARoleWithNoGrantsOrAnUnknownRole() {
        assertTrue(repository.getPermissionsForRole("SUPERADMIN").isEmpty());
        assertTrue(repository.getPermissionsForRole("NOPE").isEmpty());
    }

    @Test
    void silentlyIgnoresAStrayPermissionStringFromHandEditedSql() {
        grant("ADMIN", "APPROVE_NOTES");
        grant("ADMIN", "PERMISSION_THAT_NO_LONGER_EXISTS");

        // The unknown string is dropped, not thrown — one bad hand-edited row must not
        // break every permission check for that role.
        assertEquals(Set.of(Permission.APPROVE_NOTES), repository.getPermissionsForRole("ADMIN"));
    }
}
