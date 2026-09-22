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
 * same precedent as the other *RepositoryTest classes). {@code sede_id} is a plain external-id
 * String now (see {@code V3__app_user_sede_external_id.sql}), not a {@code SEDE(id)} FK — so
 * {@code sedeName} is always null here; the local SEDE table only exists in this schema for other
 * fixtures, not for resolving it.
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

    private void insertUser(String username, String role, String sedeId, boolean bypass) {
        jdbc.update("INSERT INTO APP_USER (username, role_id, sede_id, bypass_group_check) "
                + "SELECT ?, id, ?, ? FROM ROLE WHERE name = ?", username, sedeId, bypass ? 1 : 0, role);
    }

    @Test
    void resolvesRoleAndExternalSedeIdButNeverALocalSedeName() {
        insertUser("jperez", "ADMIN", "glpi-loc-42", false);

        AppUserRecord u = repository.findByUsername("jperez").orElseThrow();

        assertEquals("jperez", u.username());
        assertEquals("ADMIN", u.role());
        assertEquals("glpi-loc-42", u.sedeId());
        assertNull(u.sedeName());
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
