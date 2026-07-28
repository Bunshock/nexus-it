package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

public class SqliteUserRoleService implements IUserRoleService {

    private final Supplier<Connection> connector;

    public SqliteUserRoleService() {
        this.connector = () -> {
            try { return DatabaseService.getInstance().getConnection(); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
    }

    SqliteUserRoleService(Supplier<Connection> connector) {
        this.connector = connector;
    }

    @Override
    public String getRole(String username) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("SELECT role FROM USER_ROLE WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("role") : ROLE_USER;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up role for " + username, e);
        }
    }
}
