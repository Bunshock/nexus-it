package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import com.bunshock.note_app_for_it_frontend.models.Permission;

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
             PreparedStatement ps = c.prepareStatement("""
                 SELECT r.name FROM APP_USER u JOIN ROLE r ON r.id = u.role_id WHERE u.username = ?
                 """)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("name") : ROLE_USER;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up role for " + username, e);
        }
    }

    @Override
    public boolean isRegistered(String username) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM APP_USER WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check registration for " + username, e);
        }
    }

    @Override
    public Integer getSedeId(String username) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("SELECT sede_id FROM APP_USER WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int sedeId = rs.getInt("sede_id");
                return rs.wasNull() ? null : sedeId;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up sede for " + username, e);
        }
    }

    @Override
    public boolean hasGroupCheckBypass(String username) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("SELECT bypass_group_check FROM APP_USER WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("bypass_group_check") != 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up group-check bypass for " + username, e);
        }
    }

    @Override
    public Set<Permission> getPermissionsForRole(String role) {
        Set<Permission> permissions = new HashSet<>();
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("""
                 SELECT rp.permission FROM ROLE_PERMISSION rp JOIN ROLE r ON r.id = rp.role_id
                 WHERE r.name = ?
                 """)) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // A stray/typo'd permission string from direct SQL editing that doesn't
                    // match a real enum value is simply not grantable to anything — ignore it
                    // rather than throw, same "fail safe" precedent as elsewhere in this app.
                    try {
                        permissions.add(Permission.valueOf(rs.getString("permission")));
                    } catch (IllegalArgumentException unknownPermission) {
                        // ignored — see comment above
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up permissions for role " + role, e);
        }
        return permissions;
    }
}
