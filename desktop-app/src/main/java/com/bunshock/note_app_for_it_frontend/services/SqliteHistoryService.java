package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;

public class SqliteHistoryService implements IHistoryService {

    private final DatabaseService db = DatabaseService.getInstance();

    @Override
    public int save(NoteReport report) {
        try (Connection c = db.getConnection()) {
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
        PreparedStatement ps = c.prepareStatement(
            "INSERT INTO NOTE_REPORT (created_at, profile_type, glpi_synced) VALUES (?, ?, ?)",
            PreparedStatement.RETURN_GENERATED_KEYS);
        ps.setString(1, report.getCreatedAt().toString());
        ps.setString(2, report.getProfileType());
        ps.setInt(3, report.isGlpiSynced() ? 1 : 0);
        ps.executeUpdate();
        return ps.getGeneratedKeys().getInt(1);
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
        PreparedStatement ps = c.prepareStatement(
            "INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, serial_number, a_f, quantity, observations) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        for (NoteReportItem item : items) {
            ps.setInt(1, reportId);
            ps.setString(2, item.getTypeName());
            ps.setString(3, item.getBrandName());
            ps.setString(4, item.getModelName());
            ps.setString(5, item.getSerialNumber());
            ps.setString(6, item.getAf());
            ps.setInt(7, item.getQuantity());
            ps.setString(8, item.getObservations());
            ps.addBatch();
        }
        ps.executeBatch();
    }

    @Override
    public List<NoteReport> getAll() {
        List<NoteReport> result = new ArrayList<>();
        String sql = "SELECT id, created_at, profile_type, glpi_synced FROM NOTE_REPORT ORDER BY created_at DESC";
        try (Connection c = db.getConnection();
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) {
                NoteReport r = new NoteReport();
                r.setId(rs.getInt("id"));
                r.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
                r.setProfileType(rs.getString("profile_type"));
                r.setGlpiSynced(rs.getInt("glpi_synced") == 1);
                result.add(r);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load history", e);
        }
        return result;
    }

    @Override
    public NoteReport getById(int id) {
        try (Connection c = db.getConnection()) {
            PreparedStatement ps = c.prepareStatement(
                "SELECT id, created_at, profile_type, glpi_synced FROM NOTE_REPORT WHERE id = ?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            NoteReport r = new NoteReport();
            r.setId(rs.getInt("id"));
            r.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
            r.setProfileType(rs.getString("profile_type"));
            r.setGlpiSynced(rs.getInt("glpi_synced") == 1);
            r.setItems(loadItems(c, id));
            return r;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load report " + id, e);
        }
    }

    private List<NoteReportItem> loadItems(Connection c, int reportId) throws SQLException {
        List<NoteReportItem> items = new ArrayList<>();
        PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM NOTE_ITEM WHERE note_id = ?");
        ps.setInt(1, reportId);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            NoteReportItem item = new NoteReportItem();
            item.setTypeName(rs.getString("type_name"));
            item.setBrandName(rs.getString("brand_name"));
            item.setModelName(rs.getString("model_name"));
            item.setSerialNumber(rs.getString("serial_number"));
            item.setAf(rs.getString("a_f"));
            item.setQuantity(rs.getInt("quantity"));
            item.setObservations(rs.getString("observations"));
            items.add(item);
        }
        return items;
    }

    @Override
    public void markGlpiSynced(int reportId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE NOTE_REPORT SET glpi_synced = 1 WHERE id = ?")) {
            ps.setInt(1, reportId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark GLPI synced", e);
        }
    }
}
