package com.bunshock.note_app_for_it.rbac;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JDBC test against embedded H2 (app-user-repository-test-schema.sql — H2-native stand-in,
 * same precedent as the other *RepositoryTest classes). {@link AppUserRepository}'s {@code APP_USER
 * JOIN ROLE LEFT JOIN SEDE} query had no direct coverage before the login/{@code /me} responses
 * started carrying {@code sedeName}.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@JdbcTest
@Sql("/app-user-repository-test-schema.sql")
class AppUserRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private AppUserRepository repository;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM APP_USER");
        jdbc.update("DELETE FROM SEDE");
        repository = new AppUserRepository(jdbc);
    }

    private int insertSede(String name) {
        jdbc.update("INSERT INTO SEDE (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM SEDE WHERE name = ?", Integer.class, name);
    }

    private void insertUser(String username, String role, Integer sedeId, boolean bypass) {
        jdbc.update("INSERT INTO APP_USER (username, role_id, sede_id, bypass_group_check) "
                + "SELECT ?, id, ?, ? FROM ROLE WHERE name = ?", username, sedeId, bypass ? 1 : 0, role);
    }

    @Test
    void resolvesRoleNameAndSedeName() {
        int casaCentral = insertSede("Casa Central");
        insertUser("jperez", "ADMIN", casaCentral, false);

        AppUserRecord u = repository.findByUsername("jperez").orElseThrow();

        assertEquals("jperez", u.username());
        assertEquals("ADMIN", u.role());
        assertEquals(casaCentral, u.sedeId());
        assertEquals("Casa Central", u.sedeName());
        assertFalse(u.bypassGroupCheck());
    }

    @Test
    void sedeIdAndSedeNameAreNullWhenNoSedeAssigned() {
        insertUser("nosede", "USER", null, false);

        AppUserRecord u = repository.findByUsername("nosede").orElseThrow();

        assertNull(u.sedeId());
        assertNull(u.sedeName());
    }

    @Test
    void bypassGroupCheckFlagRoundTrips() {
        insertUser("intern", "USER", null, true);

        assertTrue(repository.findByUsername("intern").orElseThrow().bypassGroupCheck());
    }

    @Test
    void emptyWhenNoRow() {
        assertEquals(Optional.empty(), repository.findByUsername("ghost"));
    }
}
