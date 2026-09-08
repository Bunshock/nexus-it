package com.bunshock.note_app_for_it.audit;

import com.bunshock.note_app_for_it.audit.dto.AuditAdminActionEntry;
import com.bunshock.note_app_for_it.audit.dto.AuditItemStatusEntry;
import com.bunshock.note_app_for_it.audit.dto.AuditLoginEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * §6 — writes AND reads all 4 {@code AUDIT_*} tables (consolidates the earlier, login-only
 * {@code AuditLoginRepository}). "The app never writes an audit row directly" (§6) — every
 * {@code record*} method here is called as a side effect of a real mutation elsewhere
 * ({@code AuthController.login()}, {@code NotesRepository}'s item-status/stock-moving methods),
 * never by an endpoint the app calls to write an audit row on purpose.
 *
 * <p>Only 3 of the 4 tables have a read endpoint in the contract (§6) — {@code AUDIT_STOCK} has
 * none; it's written for completeness/future use but not exposed via GET here.
 */
@Repository
public class AuditRepository {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    public void recordLogin(String username, boolean success, String failureReason) {
        jdbc.update(
                "INSERT INTO AUDIT_LOGIN (username, success, failure_reason, attempted_at) VALUES (?, ?, ?, ?)",
                username, success ? 1 : 0, failureReason, LocalDateTime.now());
    }

    /** {@code quantity} is always 1 for a whole-item transition (asset GLPI sync, whole-item return/lost). */
    public void recordItemStatusChange(int itemId, String statusKind, String oldStatus, String newStatus,
            String reason, int quantity, String username) {
        jdbc.update("""
                INSERT INTO AUDIT_ITEM_STATUS (item_id, status_kind, old_status, new_status, reason, quantity, username, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, itemId, statusKind, oldStatus, newStatus, reason, quantity, username, LocalDateTime.now());
    }

    /** Resolves {@code BRAND_TYPE_LINK} internally — every real caller already has (modelId, brandId, typeId) on hand. */
    public void recordStockChange(int modelId, int brandId, int typeId, int sedeId, String username,
            int oldStock, int newStock, String reason) {
        List<Integer> linkIds = jdbc.query(
                "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?",
                (rs, rowNum) -> rs.getInt(1), typeId, brandId);
        if (linkIds.isEmpty()) {
            // Should not happen in practice — a stock change always goes through
            // CatalogRepository.ensureBrandTypeLink() first — but audit writes must never crash
            // the actual stock mutation they're recording, so this is a silent no-op, not a throw.
            return;
        }
        jdbc.update("""
                INSERT INTO AUDIT_STOCK (brand_type_id, model_id, sede_id, username, old_stock, new_stock, reason, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, linkIds.get(0), modelId, sedeId, username, oldStock, newStock, reason, LocalDateTime.now());
    }

    public void recordAdminAction(String username, String action, String targetType, String targetId,
            String oldValue, String newValue, String reason) {
        jdbc.update("""
                INSERT INTO AUDIT_ADMIN_ACTION (username, action, target_type, target_id, old_value, new_value, reason, performed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, username, action, targetType, targetId, oldValue, newValue, reason, LocalDateTime.now());
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    public List<AuditItemStatusEntry> getItemStatusAudit(Integer noteId, Integer itemId, Integer page, Integer size) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.item_id, a.status_kind, a.old_status, a.new_status, a.reason, a.quantity, a.username, a.changed_at
                FROM AUDIT_ITEM_STATUS a
                """);
        List<Object> params = new ArrayList<>();
        if (noteId != null) {
            sql.append(" JOIN NOTE_ITEM ni ON ni.id = a.item_id WHERE ni.note_id = ?");
            params.add(noteId);
            if (itemId != null) {
                sql.append(" AND a.item_id = ?");
                params.add(itemId);
            }
        } else if (itemId != null) {
            sql.append(" WHERE a.item_id = ?");
            params.add(itemId);
        } else {
            sql.append(" WHERE 1=1");
        }
        sql.append(" ORDER BY a.changed_at DESC, a.id DESC");
        appendPaging(sql, params, page, size);

        return jdbc.query(sql.toString(), (rs, rowNum) -> new AuditItemStatusEntry(
                rs.getInt("id"), rs.getInt("item_id"), rs.getString("status_kind"),
                rs.getString("old_status"), rs.getString("new_status"), rs.getString("reason"),
                rs.getInt("quantity"), rs.getString("username"), timestampToString(rs, "changed_at")),
                params.toArray());
    }

    public List<AuditLoginEntry> getLoginAudit(String username, Integer page, Integer size) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, username, success, failure_reason, attempted_at FROM AUDIT_LOGIN WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            sql.append(" AND username = ?");
            params.add(username);
        }
        sql.append(" ORDER BY attempted_at DESC, id DESC");
        appendPaging(sql, params, page, size);

        return jdbc.query(sql.toString(), (rs, rowNum) -> new AuditLoginEntry(
                rs.getInt("id"), rs.getString("username"), rs.getInt("success") == 1,
                rs.getString("failure_reason"), timestampToString(rs, "attempted_at")),
                params.toArray());
    }

    public List<AuditAdminActionEntry> getAdminActionAudit(String actor, String targetType, Integer page, Integer size) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, username, action, target_type, target_id, old_value, new_value, reason, performed_at
                FROM AUDIT_ADMIN_ACTION WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND username = ?");
            params.add(actor);
        }
        if (targetType != null && !targetType.isBlank()) {
            sql.append(" AND target_type = ?");
            params.add(targetType);
        }
        sql.append(" ORDER BY performed_at DESC, id DESC");
        appendPaging(sql, params, page, size);

        return jdbc.query(sql.toString(), (rs, rowNum) -> new AuditAdminActionEntry(
                rs.getInt("id"), rs.getString("username"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("old_value"), rs.getString("new_value"), rs.getString("reason"),
                timestampToString(rs, "performed_at")),
                params.toArray());
    }

    // OFFSET/FETCH NEXT is ANSI SQL, supported identically by both SQL Server (2012+) and H2 —
    // unlike LIMIT/OFFSET (MySQL/Postgres/SQLite-style), which SQL Server doesn't support at all.
    private void appendPaging(StringBuilder sql, List<Object> params, Integer page, Integer size) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNum = page == null ? 0 : Math.max(page, 0);
        sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(pageNum * pageSize);
        params.add(pageSize);
    }

    private static String timestampToString(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime().toString();
    }
}
