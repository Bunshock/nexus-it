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

    private static AppUserRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        // wasNull() reflects only the immediately-preceding getter call, so the null check
        // on sede_id must happen right here — not inline inside the record constructor
        // below, where a later getString()/getInt() call would silently overwrite it.
        String username = rs.getString("username");
        String role = rs.getString("role");
        int sedeIdRaw = rs.getInt("sede_id");
        Integer sedeId = rs.wasNull() ? null : sedeIdRaw;
        boolean bypassGroupCheck = rs.getInt("bypass_group_check") != 0;
        return new AppUserRecord(username, role, sedeId, bypassGroupCheck);
    }
}
