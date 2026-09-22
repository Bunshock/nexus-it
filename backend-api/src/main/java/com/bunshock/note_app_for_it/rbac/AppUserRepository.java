package com.bunshock.note_app_for_it.rbac;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Ported from the desktop app's {@code SqliteUserRoleService} — {@code APP_USER} JOIN {@code ROLE}. */
@Repository
public class AppUserRepository {

    // No JOIN to SEDE anymore: sede_id is now an external id (a GLPI Location id, or a placeholder
    // pending M3), a different id space from the local SEDE mirror's own int ids — see
    // V3__app_user_sede_external_id.sql. sede_name has no local source until M3 wires up a real
    // catalog-backed lookup, so mapRow always leaves it null.
    private static final String SELECT_BASE =
            "SELECT u.username, r.name AS role, u.sede_id, u.bypass_group_check " +
            "FROM APP_USER u JOIN ROLE r ON r.id = u.role_id ";

    private final JdbcTemplate jdbc;

    public AppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUserRecord> findByUsername(String username) {
        List<AppUserRecord> rows = jdbc.query(SELECT_BASE + "WHERE u.username = ?", AppUserRepository::mapRow, username);
        return rows.stream().findFirst();
    }

    /** The technician's own AES-encrypted GLPI {@code user_token} (F1), empty if never set. */
    public Optional<String> findGlpiTokenEncrypted(String username) {
        List<String> rows = jdbc.query(
                "SELECT glpi_token_encrypted FROM APP_USER WHERE username = ?",
                (rs, rowNum) -> rs.getString("glpi_token_encrypted"), username);
        return rows.stream().findFirst().filter(s -> s != null && !s.isBlank());
    }

    public void setGlpiTokenEncrypted(String username, String encrypted) {
        jdbc.update("UPDATE APP_USER SET glpi_token_encrypted = ? WHERE username = ?", encrypted, username);
    }

    private static AppUserRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        String username = rs.getString("username");
        String role = rs.getString("role");
        String sedeId = rs.getString("sede_id"); // null natively when unset, no wasNull() needed
        String sedeName = null; // TODO(M3): resolve via the GLPI-backed catalog once it exists
        boolean bypassGroupCheck = rs.getInt("bypass_group_check") != 0;
        return new AppUserRecord(username, role, sedeId, sedeName, bypassGroupCheck);
    }
}
