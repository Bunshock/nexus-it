package com.bunshock.note_app_for_it_frontend.services.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import com.bunshock.note_app_for_it_frontend.services.core.DatabaseService;
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
    public void recordLoginAttempt(String username, boolean success, String failureReason) {
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO AUDIT_LOGIN (username, success, failure_reason, attempted_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, username);
            ps.setInt(2, success ? 1 : 0);
            ps.setString(3, failureReason);
            ps.setString(4, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record login attempt", e);
        }
    }

    @Override
    public int countRecentFailedLoginAttempts(String username, int windowMinutes) {
        String since = LocalDateTime.now().minusMinutes(windowMinutes).toString();
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM AUDIT_LOGIN
                WHERE username = ? AND success = 0 AND attempted_at > ?
                """)) {
            ps.setString(1, username);
            ps.setString(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count recent failed login attempts", e);
        }
    }

    @Override
    public void recordStockChange(int brandId, int typeId, int modelId, int sedeId, String username,
            int oldStock, int newStock, String reason) {
        try (Connection c = connector.get()) {
            int brandTypeId = findBrandTypeLinkId(c, brandId, typeId);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO AUDIT_STOCK
                    (brand_type_id, model_id, sede_id, username, old_stock, new_stock, reason, changed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setInt(1, brandTypeId);
                ps.setInt(2, modelId);
                ps.setInt(3, sedeId);
                ps.setString(4, username);
                ps.setInt(5, oldStock);
                ps.setInt(6, newStock);
                ps.setString(7, reason);
                ps.setString(8, LocalDateTime.now().toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record stock change", e);
        }
    }

    // The link is guaranteed to already exist by the time a stock change happens — setModelStock()
    // itself calls SqliteEquipmentService's own ensureBrandTypeLink() before this audit call ever
    // runs (see DatabaseSectionController's stock dialogs).
    private int findBrandTypeLinkId(Connection c, int brandId, int typeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM BRAND_TYPE_LINK WHERE brand_id = ? AND type_id = ?")) {
            ps.setInt(1, brandId);
            ps.setInt(2, typeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("No BRAND_TYPE_LINK found for brandId=" + brandId + ", typeId=" + typeId);
            }
        }
    }

    @Override
    public void recordItemStatusChange(int itemId, String statusKind, String oldStatus, String newStatus,
            String reason, int quantity, String username) {
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO AUDIT_ITEM_STATUS
                (item_id, status_kind, old_status, new_status, reason, quantity, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setInt(1, itemId);
            ps.setString(2, statusKind);
            ps.setString(3, oldStatus);
            ps.setString(4, newStatus);
            ps.setString(5, reason);
            ps.setInt(6, quantity);
            ps.setString(7, username);
            ps.setString(8, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record item status change", e);
        }
    }

    @Override
    public void recordAdminAction(String username, String action, String targetType, String targetId,
            String oldValue, String newValue, String reason) {
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO AUDIT_ADMIN_ACTION
                (username, action, target_type, target_id, old_value, new_value, reason, performed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, username);
            ps.setString(2, action);
            ps.setString(3, targetType);
            ps.setString(4, targetId);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.setString(7, reason);
            ps.setString(8, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record admin action", e);
        }
    }
}
