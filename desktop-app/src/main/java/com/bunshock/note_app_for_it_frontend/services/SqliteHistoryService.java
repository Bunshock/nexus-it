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
import com.bunshock.note_app_for_it_frontend.models.ReturnStatus;

public class SqliteHistoryService implements IHistoryService {

    // Profile types that identify a Préstamo note — same case/accent variants
    // HistoryController.PROFILE_TYPE_LABEL_TO_RAW already has to tolerate, since profile_type
    // is stored raw (see CLAUDE.md) and its casing differs by how the row was created.
    private static final List<String> PRESTAMO_PROFILE_TYPES = List.of("PRÉSTAMO", "PRESTAMO", "Préstamo");

    private static boolean isPrestamo(String profileType) {
        return profileType != null && PRESTAMO_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase);
    }

    private static final String LIST_BASE_SQL = """
        SELECT r.id, r.created_at, r.profile_type,
               r.technician_name AS author_name,
               r.technician_dni AS author_dni,
               COALESCE(e.user_name, pv.name, '') AS recipient,
               COALESCE(e.motivo, p.motivo, '') AS motivo,
               SUM(CASE WHEN ig.status = 'PENDING'  THEN 1 ELSE 0 END) AS pending_count,
               SUM(CASE WHEN ig.status = 'SYNCED'   THEN 1 ELSE 0 END) AS synced_count,
               SUM(CASE WHEN ig.status = 'REJECTED' THEN 1 ELSE 0 END) AS rejected_count,
               SUM(CASE WHEN ia.item_id IS NOT NULL THEN 1 ELSE 0 END) AS asset_count,
               SUM(CASE WHEN i.id IS NOT NULL AND ia.item_id IS NULL THEN 1 ELSE 0 END) AS countable_count,
               SUM(CASE WHEN ir.status = 'PENDING'  THEN 1 ELSE 0 END) AS return_pending_count,
               SUM(CASE WHEN ir.status = 'RETURNED' THEN 1 ELSE 0 END) AS returned_count,
               SUM(CASE WHEN ir.status = 'LOST'     THEN 1 ELSE 0 END) AS lost_count
        FROM NOTE_REPORT r
        LEFT JOIN NOTE_ENTREGA_DEVOLUCION   e  ON e.note_report_id  = r.id
        LEFT JOIN NOTE_PROVEEDOR            p  ON p.note_report_id  = r.id
        LEFT JOIN PROVIDER                  pv ON pv.id             = p.provider_id
        LEFT JOIN NOTE_ITEM                 i  ON i.note_id         = r.id
        LEFT JOIN NOTE_ITEM_ASSET           ia ON ia.item_id        = i.id
        LEFT JOIN NOTE_ITEM_GLPI_TRACKING   ig ON ig.item_id         = i.id
        LEFT JOIN NOTE_ITEM_RETURN_TRACKING ir ON ir.item_id         = i.id
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
                insertItems(c, reportId, report.getItems(), isPrestamo(report.getProfileType()));
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
            "INSERT INTO NOTE_REPORT (created_at, profile_type, technician_name, technician_dni, observations, sede_id) VALUES (?, ?, ?, ?, ?, ?)",
            PreparedStatement.RETURN_GENERATED_KEYS);
        ps.setString(1, report.getCreatedAt().toString());
        ps.setString(2, report.getProfileType());
        ps.setString(3, report.getAuthorName());
        ps.setString(4, report.getAuthorDni());
        ps.setString(5, report.getObservations());
        ps.setInt(6, report.getSedeId());
        ps.executeUpdate();
        return ps.getGeneratedKeys().getInt(1);
    }

    private void insertProfileDetail(Connection c, int reportId, NoteReport report) throws SQLException {
        if (report.getProviderName() != null) {
            PreparedStatement ps = c.prepareStatement("""
                INSERT INTO NOTE_PROVEEDOR
                    (note_report_id, provider_id, cuit, motivo, responsible_name, responsible_dni)
                VALUES (?, ?, ?, ?, ?, ?)
                """);
            ps.setInt(1, reportId);
            ps.setInt(2, report.getProviderId());
            ps.setString(3, report.getCuit());
            ps.setString(4, report.getMotivo());
            ps.setString(5, report.getResponsibleName());
            ps.setString(6, report.getResponsibleDni());
            ps.executeUpdate();
        } else {
            PreparedStatement ps = c.prepareStatement("""
                INSERT INTO NOTE_ENTREGA_DEVOLUCION
                    (note_report_id, user_name, user_dni, user_email, motivo, failure_cause, failure_details, area_evento)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """);
            ps.setInt(1, reportId);
            ps.setString(2, report.getUserName());
            ps.setString(3, report.getUserDni());
            ps.setString(4, report.getUserEmail());
            ps.setString(5, report.getMotivo());
            ps.setString(6, report.getFailureCause());
            ps.setString(7, report.getFailureDetails());
            ps.setString(8, report.getAreaEvento());
            ps.executeUpdate();
        }
    }

    // The base NOTE_ITEM insert can't be batched like the old single-table insert was — each
    // subtype-table row needs its own base row's generated id first. Only the 4 subtype
    // PreparedStatements below are batched. A subtype row is only ever inserted when that
    // dimension actually applies (see the 5-table split in DatabaseService/RemoteDatabaseService)
    // — trusting the exact same caller-supplied status values this method already trusted before
    // the split, not re-deriving the applicability rule a second time here.
    private void insertItems(Connection c, int reportId, List<NoteReportItem> items, boolean isPrestamo) throws SQLException {
        if (items == null) return;
        PreparedStatement itemPs = c.prepareStatement("""
            INSERT INTO NOTE_ITEM (note_id, type_id, brand_id, model_id, observations)
            VALUES (?, ?, ?, ?, ?)
            """, PreparedStatement.RETURN_GENERATED_KEYS);
        PreparedStatement assetPs = c.prepareStatement(
            "INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f) VALUES (?, ?, ?)");
        PreparedStatement countablePs = c.prepareStatement(
            "INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity) VALUES (?, ?)");
        PreparedStatement glpiPs = c.prepareStatement("""
            INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
            VALUES (?, ?, ?, ?)
            """);
        PreparedStatement returnPs = c.prepareStatement("""
            INSERT INTO NOTE_ITEM_RETURN_TRACKING (item_id, status, rejection_reason, status_updated_at)
            VALUES (?, ?, ?, ?)
            """);

        // Every item on a Préstamo note (asset or countable alike) needs its own return
        // tracked; every other note type's items are simply not applicable.
        ReturnStatus returnStatus = isPrestamo ? ReturnStatus.PENDING : ReturnStatus.N_A;
        for (NoteReportItem item : items) {
            itemPs.setInt(1, reportId);
            itemPs.setInt(2, item.getTypeId());
            itemPs.setInt(3, item.getBrandId());
            itemPs.setInt(4, item.getModelId());
            itemPs.setString(5, item.getObservations());
            itemPs.executeUpdate();
            int itemId = itemPs.getGeneratedKeys().getInt(1);

            if (item.isAsset()) {
                assetPs.setInt(1, itemId);
                assetPs.setString(2, item.getSerialNumber());
                assetPs.setString(3, item.getAf());
                assetPs.addBatch();
            } else {
                countablePs.setInt(1, itemId);
                countablePs.setInt(2, item.getQuantity());
                countablePs.addBatch();
            }

            if (item.getGlpiStatus() != GlpiStatus.N_A) {
                glpiPs.setInt(1, itemId);
                glpiPs.setString(2, item.getGlpiStatus().toDbString());
                glpiPs.setString(3, null);
                glpiPs.setString(4, null);
                glpiPs.addBatch();
            }
            if (returnStatus != ReturnStatus.N_A) {
                returnPs.setInt(1, itemId);
                returnPs.setString(2, returnStatus.toDbString());
                returnPs.setString(3, null);
                returnPs.setString(4, null);
                returnPs.addBatch();
            }
        }
        assetPs.executeBatch();
        countablePs.executeBatch();
        glpiPs.executeBatch();
        returnPs.executeBatch();
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
            sql.append(" AND COALESCE(e.user_name, pv.name, '') LIKE ?");
            params.add("%" + filter.getRecipientSearch().trim() + "%");
        }
        if (filter.getAuthorSearch() != null && !filter.getAuthorSearch().isBlank()) {
            // Can't reference the "author_name" SELECT alias here — SQLite tolerates it but
            // SQL Server doesn't, and this query runs against both (see ServiceLocator's dual
            // wiring of SqliteHistoryService for local vs. remote). Repeat the column instead.
            sql.append(" AND COALESCE(r.technician_name, '') LIKE ?");
            params.add("%" + filter.getAuthorSearch().trim() + "%");
        }
        if (hasValues(filter.getItemTypes()) || hasValues(filter.getItemBrands()) || hasValues(filter.getItemModels())) {
            sql.append(" AND r.id IN (SELECT DISTINCT ni.note_id FROM NOTE_ITEM ni WHERE 1=1");
            appendInViaCatalog(sql, params, "ni.type_id",  "TYPE",  filter.getItemTypes());
            appendInViaCatalog(sql, params, "ni.brand_id", "BRAND", filter.getItemBrands());
            appendInViaCatalog(sql, params, "ni.model_id", "MODEL", filter.getItemModels());
            sql.append(")");
        }
        appendInViaCatalog(sql, params, "r.sede_id", "SEDE", filter.getSedes());

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

    // Filters NOTE_ITEM by a catalog id whose current-or-historical name matches one of the
    // given values — regardless of deprecated status, so filtering by a name a type/brand/model
    // was later renamed away from still finds the notes that used it at the time.
    private void appendInViaCatalog(StringBuilder sql, List<Object> params, String idColumn,
            String catalogTable, List<String> names) {
        if (!hasValues(names)) return;
        sql.append(" AND ").append(idColumn).append(" IN (SELECT id FROM ").append(catalogTable)
            .append(" WHERE name IN (");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append("))");
        params.addAll(names);
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
        int pending  = r.getPendingItemCount();
        int synced   = r.getSyncedItemCount();
        int rejected = r.getRejectedItemCount();
        return switch (status) {
            // Not total == 0 — a Préstamo note's assets are all glpi_status N_A (see CLAUDE.md's
            // "Préstamo assets are deliberately excluded from GLPI sync"), so getAssetItemCount()
            // alone would be > 0 for such a note even though none of its assets are GLPI-tracked,
            // silently excluding it from the "Sin GLPI" filter. A note has nothing GLPI-tracked
            // whenever none of pending/synced/rejected are non-zero, whether that's because it
            // has no assets at all or because every asset on it is N_A.
            case "N_A"      -> (pending + synced + rejected) == 0;
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
        return queryDistinct("""
            SELECT DISTINCT t.name FROM NOTE_ITEM ni JOIN TYPE t ON t.id = ni.type_id ORDER BY t.name
            """, java.util.Collections.emptyList());
    }

    @Override
    public List<String> getDistinctSedes() {
        return queryDistinct("""
            SELECT DISTINCT sd.name FROM NOTE_REPORT r JOIN SEDE sd ON sd.id = r.sede_id ORDER BY sd.name
            """, java.util.Collections.emptyList());
    }

    @Override
    public List<String> getDistinctItemBrands(List<String> types) {
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT b.name FROM NOTE_ITEM ni
            JOIN BRAND b ON b.id = ni.brand_id
            JOIN TYPE t ON t.id = ni.type_id
            WHERE 1=1
            """);
        List<String> params = new ArrayList<>();
        if (hasValues(types)) {
            sql.append(" AND t.name IN (");
            for (int i = 0; i < types.size(); i++) { if (i > 0) sql.append(","); sql.append("?"); }
            sql.append(")");
            params.addAll(types);
        }
        sql.append(" ORDER BY b.name");
        return queryDistinct(sql.toString(), params);
    }

    @Override
    public List<String> getDistinctItemModels(List<String> types, List<String> brands) {
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT m.name FROM NOTE_ITEM ni
            JOIN MODEL m ON m.id = ni.model_id
            JOIN BRAND b ON b.id = ni.brand_id
            JOIN TYPE t ON t.id = ni.type_id
            WHERE 1=1
            """);
        List<String> params = new ArrayList<>();
        if (hasValues(types)) {
            sql.append(" AND t.name IN (");
            for (int i = 0; i < types.size(); i++) { if (i > 0) sql.append(","); sql.append("?"); }
            sql.append(")");
            params.addAll(types);
        }
        if (hasValues(brands)) {
            sql.append(" AND b.name IN (");
            for (int i = 0; i < brands.size(); i++) { if (i > 0) sql.append(","); sql.append("?"); }
            sql.append(")");
            params.addAll(brands);
        }
        sql.append(" ORDER BY m.name");
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
        r.setAuthorDni(rs.getString("author_dni"));
        r.setRecipientDisplay(rs.getString("recipient"));
        r.setMotivo(rs.getString("motivo"));
        r.setAssetItemCount(rs.getInt("asset_count"));
        r.setCountableItemCount(rs.getInt("countable_count"));
        r.setPendingItemCount(rs.getInt("pending_count"));
        r.setSyncedItemCount(rs.getInt("synced_count"));
        r.setRejectedItemCount(rs.getInt("rejected_count"));
        r.setReturnPendingItemCount(rs.getInt("return_pending_count"));
        r.setReturnedItemCount(rs.getInt("returned_count"));
        r.setLostItemCount(rs.getInt("lost_count"));
        return r;
    }

    @Override
    public NoteReport getById(int id) {
        String sql = """
            SELECT r.id, r.created_at, r.profile_type,
                   r.technician_name AS author_name,
                   r.technician_dni AS author_dni,
                   COALESCE(r.observations, '') AS observations,
                   COALESCE(sd.name, '') AS sede,
                   COALESCE(e.user_name, '')    AS user_name,
                   COALESCE(e.user_dni, '')     AS user_dni,
                   COALESCE(e.user_email, '')   AS user_email,
                   COALESCE(e.motivo, p.motivo, '') AS motivo,
                   COALESCE(e.failure_cause, '')   AS failure_cause,
                   COALESCE(e.failure_details, '') AS failure_details,
                   COALESCE(e.area_evento, '')     AS area_evento,
                   COALESCE(pv.name, '') AS provider_name,
                   COALESCE(p.provider_id, 0) AS provider_id,
                   COALESCE(p.cuit, '')          AS cuit,
                   COALESCE(p.responsible_name, '') AS responsible_name,
                   COALESCE(p.responsible_dni, '')  AS responsible_dni
            FROM NOTE_REPORT r
            LEFT JOIN NOTE_ENTREGA_DEVOLUCION  e  ON e.note_report_id  = r.id
            LEFT JOIN NOTE_PROVEEDOR           p  ON p.note_report_id  = r.id
            LEFT JOIN PROVIDER                 pv ON pv.id             = p.provider_id
            LEFT JOIN SEDE                     sd ON sd.id             = r.sede_id
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
            r.setAuthorDni(rs.getString("author_dni"));
            r.setObservations(rs.getString("observations"));
            r.setSede(rs.getString("sede"));
            r.setUserName(rs.getString("user_name"));
            r.setUserDni(rs.getString("user_dni"));
            r.setUserEmail(rs.getString("user_email"));
            r.setMotivo(rs.getString("motivo"));
            r.setFailureCause(rs.getString("failure_cause"));
            r.setFailureDetails(rs.getString("failure_details"));
            r.setAreaEvento(rs.getString("area_evento"));
            String provName = rs.getString("provider_name");
            r.setProviderName(provName.isBlank() ? null : provName);
            r.setProviderId(rs.getInt("provider_id"));
            r.setCuit(rs.getString("cuit"));
            r.setResponsibleName(rs.getString("responsible_name"));
            r.setResponsibleDni(rs.getString("responsible_dni"));
            r.setRecipientDisplay(!provName.isBlank() ? provName : rs.getString("user_name"));
            r.setItems(loadItems(c, id));
            return r;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load report " + id, e);
        }
    }

    // Reproduces the exact same result-column names the old wide NOTE_ITEM table used to return
    // natively (is_asset, glpi_status, serial_number, etc.), now computed via LEFT JOIN/COALESCE
    // against the split subtype tables — the row-mapping code below is unchanged from before the
    // 5-table split.
    private List<NoteReportItem> loadItems(Connection c, int reportId) throws SQLException {
        List<NoteReportItem> items = new ArrayList<>();
        PreparedStatement ps = c.prepareStatement("""
            SELECT b.id, b.type_id, b.brand_id, b.model_id,
                   t.name AS type_name, br.name AS brand_name, m.name AS model_name,
                   b.observations,
                   CASE WHEN a.item_id IS NOT NULL THEN 1 ELSE 0 END AS is_asset,
                   a.serial_number, a.a_f,
                   COALESCE(ct.quantity, 1) AS quantity,
                   COALESCE(g.status, 'N_A') AS glpi_status,
                   g.rejection_reason AS glpi_rejection_reason,
                   g.status_updated_at AS glpi_status_updated_at,
                   COALESCE(rt.status, 'N_A') AS return_status,
                   rt.rejection_reason AS return_rejection_reason,
                   rt.status_updated_at AS return_status_updated_at
            FROM NOTE_ITEM b
            JOIN TYPE t ON t.id = b.type_id
            JOIN BRAND br ON br.id = b.brand_id
            JOIN MODEL m ON m.id = b.model_id
            LEFT JOIN NOTE_ITEM_ASSET           a  ON a.item_id  = b.id
            LEFT JOIN NOTE_ITEM_COUNTABLE       ct ON ct.item_id = b.id
            LEFT JOIN NOTE_ITEM_GLPI_TRACKING   g  ON g.item_id  = b.id
            LEFT JOIN NOTE_ITEM_RETURN_TRACKING rt ON rt.item_id = b.id
            WHERE b.note_id = ? ORDER BY b.id
            """);
        ps.setInt(1, reportId);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            NoteReportItem item = new NoteReportItem();
            item.setId(rs.getInt("id"));
            item.setTypeId(rs.getInt("type_id"));
            item.setBrandId(rs.getInt("brand_id"));
            item.setModelId(rs.getInt("model_id"));
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
            item.setReturnStatus(ReturnStatus.fromString(rs.getString("return_status")));
            item.setReturnRejectionReason(rs.getString("return_rejection_reason"));
            item.setReturnStatusUpdatedAt(rs.getString("return_status_updated_at"));
            items.add(item);
        }
        return items;
    }

    // ── Most-used items (pinned in ItemDialogView's Type/Brand/Model combos) ───

    @Override
    public List<String> getMostUsedTypeNames(int windowDays, int minUses, int limit) {
        String sql = """
            SELECT t.name AS name, COUNT(*) AS cnt
            FROM NOTE_ITEM ni
            JOIN NOTE_REPORT r ON r.id = ni.note_id
            JOIN TYPE t ON t.id = ni.type_id
            WHERE t.deprecated = 0 AND r.created_at >= ?
            GROUP BY t.id
            HAVING COUNT(*) >= ?
            ORDER BY cnt DESC
            LIMIT ?
            """;
        return queryMostUsed(sql, List.of(cutoff(windowDays), minUses, limit));
    }

    @Override
    public List<String> getMostUsedBrandNames(String typeName, int windowDays, int minUses, int limit) {
        String sql = """
            SELECT b.name AS name, COUNT(*) AS cnt
            FROM NOTE_ITEM ni
            JOIN NOTE_REPORT r ON r.id = ni.note_id
            JOIN TYPE t ON t.id = ni.type_id
            JOIN BRAND b ON b.id = ni.brand_id
            WHERE t.name = ? AND b.deprecated = 0 AND r.created_at >= ?
            GROUP BY b.id
            HAVING COUNT(*) >= ?
            ORDER BY cnt DESC
            LIMIT ?
            """;
        return queryMostUsed(sql, List.of(typeName, cutoff(windowDays), minUses, limit));
    }

    @Override
    public List<String> getMostUsedModelNames(String typeName, String brandName, int windowDays, int minUses, int limit) {
        String sql = """
            SELECT m.name AS name, COUNT(*) AS cnt
            FROM NOTE_ITEM ni
            JOIN NOTE_REPORT r ON r.id = ni.note_id
            JOIN TYPE t ON t.id = ni.type_id
            JOIN BRAND b ON b.id = ni.brand_id
            JOIN MODEL m ON m.id = ni.model_id
            WHERE t.name = ? AND b.name = ? AND m.deprecated = 0 AND r.created_at >= ?
            GROUP BY m.id
            HAVING COUNT(*) >= ?
            ORDER BY cnt DESC
            LIMIT ?
            """;
        return queryMostUsed(sql, List.of(typeName, brandName, cutoff(windowDays), minUses, limit));
    }

    private String cutoff(int windowDays) {
        return LocalDateTime.now().minusDays(windowDays).toString();
    }

    private List<String> queryMostUsed(String sql, List<Object> params) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ResultSet rs = ps.executeQuery();
            List<String> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getString("name"));
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load most-used items", e);
        }
    }

    // ── GLPI status update ────────────────────────────────────────────────────

    @Override
    public void updateItemGlpiStatus(int itemId, GlpiStatus status, String reason) {
        // Upsert, not a plain UPDATE — defensive: every real caller only ever transitions an
        // item that already has a NOTE_ITEM_GLPI_TRACKING row (PENDING), but an upsert is
        // correct even if that assumption is ever wrong, at no extra cost.
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT(item_id) DO UPDATE SET
                     status = excluded.status,
                     rejection_reason = excluded.rejection_reason,
                     status_updated_at = excluded.status_updated_at
                 """)) {
            ps.setInt(1, itemId);
            ps.setString(2, status.toDbString());
            ps.setString(3, reason);
            ps.setString(4, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item GLPI status", e);
        }
    }

    // ── Préstamo return status update ─────────────────────────────────────────

    @Override
    public void updateItemReturnStatus(int itemId, ReturnStatus status, String reason) {
        // Upsert — same reasoning as updateItemGlpiStatus() above.
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO NOTE_ITEM_RETURN_TRACKING (item_id, status, rejection_reason, status_updated_at)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT(item_id) DO UPDATE SET
                     status = excluded.status,
                     rejection_reason = excluded.rejection_reason,
                     status_updated_at = excluded.status_updated_at
                 """)) {
            ps.setInt(1, itemId);
            ps.setString(2, status.toDbString());
            ps.setString(3, reason);
            ps.setString(4, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item return status", e);
        }
    }

}
