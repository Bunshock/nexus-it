package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.bunshock.note_app_for_it_frontend.models.ActionAuditEntry;
import com.bunshock.note_app_for_it_frontend.models.LoginAuditEntry;

public class SqliteAuditService implements IAuditService {

    private final Supplier<Connection> connector;

    public SqliteAuditService() {
        this.connector = () -> {
            try { return DatabaseService.getInstance().getConnection(); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
    }

    SqliteAuditService(Supplier<Connection> connector) {
        this.connector = connector;
    }

    @Override
    public void logLogin(String username, boolean success, String failureReason) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO LOGIN_AUDIT (username, attempted_at, success, failure_reason) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, LocalDateTime.now().toString());
            ps.setInt(3, success ? 1 : 0);
            ps.setString(4, failureReason);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to log login attempt for " + username, e);
        }
    }

    @Override
    public void logAction(String username, String eventType, Integer noteItemId, String details) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO ACTION_AUDIT (username, event_type, occurred_at, note_item_id, details) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, eventType);
            ps.setString(3, LocalDateTime.now().toString());
            if (noteItemId != null) ps.setInt(4, noteItemId); else ps.setNull(4, java.sql.Types.INTEGER);
            ps.setString(5, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to log action " + eventType + " for " + username, e);
        }
    }

    @Override
    public List<LoginAuditEntry> getAllLogins() {
        List<LoginAuditEntry> result = new ArrayList<>();
        try (Connection c = connector.get();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT username, attempted_at, success, failure_reason FROM LOGIN_AUDIT ORDER BY attempted_at DESC")) {
            while (rs.next()) {
                result.add(new LoginAuditEntry(
                    rs.getString("username"),
                    rs.getString("attempted_at"),
                    rs.getInt("success") != 0,
                    rs.getString("failure_reason")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load login audit", e);
        }
        return result;
    }

    @Override
    public List<ActionAuditEntry> getAllActions() {
        List<ActionAuditEntry> result = new ArrayList<>();
        try (Connection c = connector.get();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT username, event_type, occurred_at, note_item_id, details FROM ACTION_AUDIT ORDER BY occurred_at DESC")) {
            while (rs.next()) {
                // wasNull() reflects only the most recently read column, so it must be captured
                // immediately after getInt() — reading it after the later getString() calls in
                // the constructor's argument list would check occurred_at's null-ness instead.
                int noteItemId = rs.getInt("note_item_id");
                Integer noteItemIdOrNull = rs.wasNull() ? null : noteItemId;
                result.add(new ActionAuditEntry(
                    rs.getString("username"),
                    rs.getString("event_type"),
                    rs.getString("occurred_at"),
                    noteItemIdOrNull,
                    rs.getString("details")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load action audit", e);
        }
        return result;
    }
}
