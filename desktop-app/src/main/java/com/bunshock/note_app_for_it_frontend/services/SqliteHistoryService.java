package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.bunshock.note_app_for_it_frontend.models.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;

public class SqliteHistoryService implements IHistoryService {

    private static final String LIST_BASE_SQL = """
        SELECT r.id, r.created_at, r.profile_type, r.technician_id,
               tp.name AS author_name,
               COALESCE(e.user_name, p.provider_name, '') AS recipient,
               SUM(CASE WHEN i.is_asset = 1 AND i.glpi_status = 'PENDING'  THEN 1 ELSE 0 END) AS pending_count,
               SUM(CASE WHEN i.is_asset = 1 AND i.glpi_status = 'SYNCED'   THEN 1 ELSE 0 END) AS synced_count,
               SUM(CASE WHEN i.is_asset = 1 AND i.glpi_status = 'REJECTED' THEN 1 ELSE 0 END) AS rejected_count,
               SUM(CASE WHEN i.is_asset = 1                                 THEN 1 ELSE 0 END) AS asset_count
        FROM NOTE_REPORT r
        LEFT JOIN TECHNICIAN_PROFILE  tp ON tp.id            = r.technician_id
        LEFT JOIN NOTE_ENTREGA_DEVOLUCION e ON e.note_report_id = r.id
        LEFT JOIN NOTE_PROVEEDOR          p ON p.note_report_id = r.id
        LEFT JOIN NOTE_ITEM               i ON i.note_id        = r.id
        """;

    private final Supplier<Connection> connector;

    public SqliteHistoryService() {
        this.connector = () -> {
            try { return DatabaseService.getInstance().getConnection(); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
    }

    SqliteHistoryService(Supplier<Connection> connector) {
        this.connector = connector;
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Override
    public int save(NoteReport report) {
        try (Connection c = connector.get()) {
            c.setAutoCommit(false);
            try {
                int reportId = insertReport(c, report);
                insertProfileDetail(c, reportId, report);
                insertItems(c, reportId, report.getItems());
                c.commit();
                return reportId;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save note report", e);
        }
    }

    private int insertReport(Connection c, NoteReport report) throws SQLException {
        Integer techId = lookupTechnicianId(c);
        PreparedStatement ps = c.prepareStatement(
            "INSERT INTO NOTE_REPORT (created_at, profile_type, technician_id) VALUES (?, ?, ?)",
            PreparedStatement.RETURN_GENERATED_KEYS);
        ps.setString(1, report.getCreatedAt().toString());
        ps.setString(2, report.getProfileType());
        if (techId != null) ps.setInt(3, techId); else ps.setNull(3, java.sql.Types.INTEGER);
        ps.executeUpdate();
        return ps.getGeneratedKeys().getInt(1);
    }

    private Integer lookupTechnicianId(Connection c) {
        try (PreparedStatement ps = c.prepareStatement(
                 "SELECT id FROM TECHNICIAN_PROFILE WHERE windows_username = ?")) {
            ps.setString(1, System.getProperty("user.name", ""));
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : null;
        } catch (SQLException ignored) { return null; }
    }

    private void insertProfileDetail(Connection c, int reportId, NoteReport report) throws SQLException {
        if (report.getProviderName() != null) {
            PreparedStatement ps = c.prepareStatement(
                "INSERT INTO NOTE_PROVEEDOR (note_report_id, provider_name, cuit, motivo) VALUES (?, ?, ?, ?)");
            ps.setInt(1, reportId);
            ps.setString(2, report.getProviderName());
            ps.setString(3, report.getCuit());
            ps.setString(4, report.getMotivo());
            ps.executeUpdate();
        } else {
            PreparedStatement ps = c.prepareStatement(
                "INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, user_dni, user_email, motivo) VALUES (?, ?, ?, ?, ?)");
            ps.setInt(1, reportId);
            ps.setString(2, report.getUserName());
            ps.setString(3, report.getUserDni());
            ps.setString(4, report.getUserEmail());
            ps.setString(5, report.getMotivo());
            ps.executeUpdate();
        }
    }

    private void insertItems(Connection c, int reportId, List<NoteReportItem> items) throws SQLException {
        if (items == null) return;
        PreparedStatement ps = c.prepareStatement("""
            INSERT INTO NOTE_ITEM
                (note_id, type_name, brand_name, model_name, serial_number, a_f,
                 quantity, observations, is_asset, glpi_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """);
        for (NoteReportItem item : items) {
            ps.setInt(1, reportId);
            ps.setString(2, item.getTypeName());
            ps.setString(3, item.getBrandName());
            ps.setString(4, item.getModelName());
            ps.setString(5, item.getSerialNumber());
            ps.setString(6, item.getAf());
            ps.setInt(7, item.getQuantity());
            ps.setString(8, item.getObservations());
            ps.setInt(9, item.isAsset() ? 1 : 0);
            ps.setString(10, item.getGlpiStatus().toDbString());
            ps.addBatch();
        }
        ps.executeBatch();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    public List<NoteReport> getAll() {
        return getFiltered(new HistoryFilter());
    }

    @Override
    public List<NoteReport> getFiltered(HistoryFilter filter) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(LIST_BASE_SQL).append(" WHERE 1=1");

        if (filter.getFromDate() != null) {
            sql.append(" AND r.created_at >= ?");
            params.add(filter.getFromDate().atStartOfDay().toString());
        }
        if (filter.getToDate() != null) {
            sql.append(" AND r.created_at < ?");
            params.add(filter.getToDate().plusDays(1).atStartOfDay().toString());
        }
        appendIn(sql, params, "r.profile_type", filter.getProfileTypes());
        if (filter.getRecipientSearch() != null && !filter.getRecipientSearch().isBlank()) {
            sql.append(" AND COALESCE(e.user_name, p.provider_name, '') LIKE ?");
            params.add("%" + filter.getRecipientSearch().trim() + "%");
        }
        if (hasValues(filter.getItemTypes()) || hasValues(filter.getItemBrands()) || hasValues(filter.getItemModels())) {
            sql.append(" AND r.id IN (SELECT DISTINCT ni.note_id FROM NOTE_ITEM ni WHERE 1=1");
            appendIn(sql, params, "ni.type_name",  filter.getItemTypes());
            appendIn(sql, params, "ni.brand_name", filter.getItemBrands());
            appendIn(sql, params, "ni.model_name", filter.getItemModels());
            sql.append(")");
        }

        sql.append(" GROUP BY r.id ORDER BY r.created_at DESC");

        List<NoteReport> all = new ArrayList<>();
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) all.add(mapSummary(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load history", e);
        }

        if (hasValues(filter.getGlpiStatuses())) {
            all = filterByGlpiStatus(all, filter.getGlpiStatuses());
        }
        return all;
    }

    private void appendIn(StringBuilder sql, List<Object> params, String column, List<String> values) {
        if (!hasValues(values)) return;
        sql.append(" AND ").append(column).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");
        params.addAll(values);
    }

    private boolean hasValues(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private List<NoteReport> filterByGlpiStatus(List<NoteReport> reports, List<String> statuses) {
        return reports.stream()
            .filter(r -> statuses.stream().anyMatch(s -> matchesGlpiStatus(r, s)))
            .toList();
    }

    private boolean matchesGlpiStatus(NoteReport r, String status) {
        int total    = r.getAssetItemCount();
        int pending  = r.getPendingItemCount();
        int synced   = r.getSyncedItemCount();
        int rejected = r.getRejectedItemCount();
        return switch (status) {
            case "N_A"      -> total == 0;
            case "PENDING"  -> pending > 0;
            case "SYNCED"   -> synced > 0;
            case "REJECTED" -> rejected > 0;
            default         -> true;
        };
    }

    @Override
    public List<NoteReport> getPendingGlpiSync() {
        return getFiltered(HistoryFilter.pendingGlpiSync());
    }

    @Override
    public List<String> getDistinctItemTypes() {
        return queryDistinct("SELECT DISTINCT type_name FROM NOTE_ITEM WHERE type_name IS NOT NULL ORDER BY type_name",
            java.util.Collections.emptyList());
    }

    @Override
    public List<String> getDistinctItemBrands(List<String> types) {
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT brand_name FROM NOTE_ITEM WHERE brand_name IS NOT NULL");
        List<String> params = new ArrayList<>();
        if (hasValues(types)) {
            sql.append(" AND type_name IN (");
            for (int i = 0; i < types.size(); i++) { if (i > 0) sql.append(","); sql.append("?"); }
            sql.append(")");
            params.addAll(types);
        }
        sql.append(" ORDER BY brand_name");
        return queryDistinct(sql.toString(), params);
    }

    @Override
    public List<String> getDistinctItemModels(List<String> types, List<String> brands) {
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT model_name FROM NOTE_ITEM WHERE model_name IS NOT NULL");
        List<String> params = new ArrayList<>();
        if (hasValues(types)) {
            sql.append(" AND type_name IN (");
            for (int i = 0; i < types.size(); i++) { if (i > 0) sql.append(","); sql.append("?"); }
            sql.append(")");
            params.addAll(types);
        }
        if (hasValues(brands)) {
            sql.append(" AND brand_name IN (");
            for (int i = 0; i < brands.size(); i++) { if (i > 0) sql.append(","); sql.append("?"); }
            sql.append(")");
            params.addAll(brands);
        }
        sql.append(" ORDER BY model_name");
        return queryDistinct(sql.toString(), params);
    }

    private List<String> queryDistinct(String sql, List<String> params) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            ResultSet rs = ps.executeQuery();
            List<String> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getString(1));
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private NoteReport mapSummary(ResultSet rs) throws SQLException {
        NoteReport r = new NoteReport();
        r.setId(rs.getInt("id"));
        r.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        r.setProfileType(rs.getString("profile_type"));
        r.setAuthorName(rs.getString("author_name"));
        r.setRecipientDisplay(rs.getString("recipient"));
        r.setAssetItemCount(rs.getInt("asset_count"));
        r.setPendingItemCount(rs.getInt("pending_count"));
        r.setSyncedItemCount(rs.getInt("synced_count"));
        r.setRejectedItemCount(rs.getInt("rejected_count"));
        return r;
    }

    @Override
    public NoteReport getById(int id) {
        String sql = """
            SELECT r.id, r.created_at, r.profile_type,
                   tp.name AS author_name,
                   COALESCE(e.user_name, '')    AS user_name,
                   COALESCE(e.user_dni, '')     AS user_dni,
                   COALESCE(e.user_email, '')   AS user_email,
                   COALESCE(e.motivo, p.motivo, '') AS motivo,
                   COALESCE(p.provider_name, '') AS provider_name,
                   COALESCE(p.cuit, '')          AS cuit
            FROM NOTE_REPORT r
            LEFT JOIN TECHNICIAN_PROFILE      tp ON tp.id            = r.technician_id
            LEFT JOIN NOTE_ENTREGA_DEVOLUCION  e ON e.note_report_id  = r.id
            LEFT JOIN NOTE_PROVEEDOR           p ON p.note_report_id  = r.id
            WHERE r.id = ?
            """;
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            NoteReport r = new NoteReport();
            r.setId(rs.getInt("id"));
            r.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
            r.setProfileType(rs.getString("profile_type"));
            r.setAuthorName(rs.getString("author_name"));
            r.setUserName(rs.getString("user_name"));
            r.setUserDni(rs.getString("user_dni"));
            r.setUserEmail(rs.getString("user_email"));
            r.setMotivo(rs.getString("motivo"));
            String provName = rs.getString("provider_name");
            r.setProviderName(provName.isBlank() ? null : provName);
            r.setCuit(rs.getString("cuit"));
            r.setRecipientDisplay(provName.isBlank() ? rs.getString("user_name") : provName);
            r.setItems(loadItems(c, id));
            return r;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load report " + id, e);
        }
    }

    private List<NoteReportItem> loadItems(Connection c, int reportId) throws SQLException {
        List<NoteReportItem> items = new ArrayList<>();
        PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM NOTE_ITEM WHERE note_id = ? ORDER BY id");
        ps.setInt(1, reportId);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            NoteReportItem item = new NoteReportItem();
            item.setId(rs.getInt("id"));
            item.setTypeName(rs.getString("type_name"));
            item.setBrandName(rs.getString("brand_name"));
            item.setModelName(rs.getString("model_name"));
            item.setSerialNumber(rs.getString("serial_number"));
            item.setAf(rs.getString("a_f"));
            item.setQuantity(rs.getInt("quantity"));
            item.setObservations(rs.getString("observations"));
            item.setAsset(rs.getInt("is_asset") == 1);
            item.setGlpiStatus(GlpiStatus.fromString(rs.getString("glpi_status")));
            item.setGlpiRejectionReason(rs.getString("glpi_rejection_reason"));
            item.setGlpiStatusUpdatedAt(rs.getString("glpi_status_updated_at"));
            items.add(item);
        }
        return items;
    }

    // ── GLPI status update ────────────────────────────────────────────────────

    @Override
    public void updateItemGlpiStatus(int itemId, GlpiStatus status, String reason) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("""
                 UPDATE NOTE_ITEM
                 SET glpi_status = ?, glpi_rejection_reason = ?, glpi_status_updated_at = ?
                 WHERE id = ?
                 """)) {
            ps.setString(1, status.toDbString());
            ps.setString(2, reason);
            ps.setString(3, LocalDateTime.now().toString());
            ps.setInt(4, itemId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item GLPI status", e);
        }
    }

}
