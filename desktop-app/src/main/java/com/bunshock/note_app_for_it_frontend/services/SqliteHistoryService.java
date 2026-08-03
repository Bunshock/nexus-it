package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.bunshock.note_app_for_it_frontend.models.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.models.ReturnAllocationBatch;
import com.bunshock.note_app_for_it_frontend.models.ReturnStatus;

public class SqliteHistoryService implements IHistoryService {

    // Profile types that identify a Préstamo note — same case/accent variants
    // HistoryController.PROFILE_TYPE_LABEL_TO_RAW already has to tolerate, since profile_type
    // is stored raw (see CLAUDE.md) and its casing differs by how the row was created.
    private static final List<String> PRESTAMO_PROFILE_TYPES = List.of("PRÉSTAMO", "PRESTAMO", "Préstamo");

    private static boolean isPrestamo(String profileType) {
        return profileType != null && PRESTAMO_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase);
    }

    // Config-driven, not hardcoded — mirrors UserNoteController.isFailureTriggerMotivo()'s
    // reasoning: renaming a motivoOptions.proveedor value in app-config.json shouldn't require a
    // code change to keep return tracking correctly gated.
    private static boolean isProviderReturnable(String profileType, String motivo) {
        if (!"Entrega - Proveedor".equalsIgnoreCase(profileType) || motivo == null) return false;
        try {
            List<String> returnable = ConfigService.getInstance().getConfig().returnableMotivosProveedor;
            return returnable != null && returnable.stream().anyMatch(motivo::equalsIgnoreCase);
        } catch (IllegalStateException notLoaded) {
            // ConfigService not loaded in this context (e.g. some test setups)
            return false;
        }
    }

    // return_pending_count/returned_count/lost_count are weighted by quantity, not row count —
    // an asset row always weighs 1 (a physical unit isn't divisible), but a countable row weighs
    // its actual quantity, split across RETURNED/LOST via the pre-aggregated `alloc` subquery
    // (NOTE_ITEM_RETURN_ALLOCATION, one row per partial action) so a partially-resolved countable
    // (e.g. 5 loaned, 3 returned, 1 lost, 1 still pending) is counted correctly instead of as one
    // indivisible unit. `alloc` is pre-aggregated to one row per item_id before joining so it can
    // never fan out the surrounding SUMs the way a raw join against its many-rows-per-item shape
    // would. The CASE-based clamp (instead of a 2-arg MAX()) keeps this portable to SQL Server,
    // which has no scalar MAX(x, y) — see "Remote SQL Server" in CLAUDE.md.
    //
    // pending_count/synced_count/rejected_count fold in the second GLPI dimension
    // (NOTE_ITEM_GLPI_RETURN_TRACKING, "synced back in") via COALESCE(igr.status, ig.status): once
    // an item's return has been validated and this second dimension has a row at all, IT becomes
    // the item's effective GLPI status for coloring purposes (the original sync-out is subsumed —
    // what matters after a return is whether GLPI now correctly reflects the item being back, not
    // whether it was correctly marked as sent out). igr only ever has rows for a returnable
    // Provider note's asset items post-validation, so this COALESCE is a no-op (falls through to
    // ig.status) for every other note/item — safe for Entrega/Devolución/Fin de Contrato/Préstamo/
    // non-returnable-Provider notes, none of which are affected by this change.
    private static final String LIST_BASE_SQL = """
        SELECT r.id, r.created_at, r.profile_type,
               r.approval_status, rr.rejection_reason,
               r.technician_name AS author_name,
               r.technician_dni AS author_dni,
               COALESCE(e.user_name, pv.name, '') AS recipient,
               COALESCE(e.motivo, p.motivo, '') AS motivo,
               COALESCE(sd.name, '') AS sede,
               SUM(CASE WHEN COALESCE(igr.status, ig.status) = 'PENDING'  THEN 1 ELSE 0 END) AS pending_count,
               SUM(CASE WHEN COALESCE(igr.status, ig.status) = 'SYNCED'   THEN 1 ELSE 0 END) AS synced_count,
               SUM(CASE WHEN COALESCE(igr.status, ig.status) = 'REJECTED' THEN 1 ELSE 0 END) AS rejected_count,
               SUM(CASE WHEN ia.item_id IS NOT NULL THEN 1 ELSE 0 END) AS asset_count,
               SUM(CASE WHEN i.id IS NOT NULL AND ia.item_id IS NULL THEN 1 ELSE 0 END) AS countable_count,
               SUM(
                   CASE WHEN ir.status IS NULL THEN 0
                        WHEN ia.item_id IS NOT NULL THEN CASE WHEN ir.status = 'PENDING' THEN 1 ELSE 0 END
                        ELSE CASE WHEN (COALESCE(ic.quantity, 0) - COALESCE(alloc.returned_qty, 0) - COALESCE(alloc.lost_qty, 0)) < 0
                                  THEN 0
                                  ELSE (COALESCE(ic.quantity, 0) - COALESCE(alloc.returned_qty, 0) - COALESCE(alloc.lost_qty, 0))
                             END
                   END
               ) AS return_pending_count,
               SUM(
                   CASE WHEN ia.item_id IS NOT NULL THEN CASE WHEN ir.status = 'RETURNED' THEN 1 ELSE 0 END
                        ELSE COALESCE(alloc.returned_qty, 0)
                   END
               ) AS returned_count,
               SUM(
                   CASE WHEN ia.item_id IS NOT NULL THEN CASE WHEN ir.status = 'LOST' THEN 1 ELSE 0 END
                        ELSE COALESCE(alloc.lost_qty, 0)
                   END
               ) AS lost_count
        FROM NOTE_REPORT r
        LEFT JOIN NOTE_REPORT_REJECTION      rr ON rr.note_report_id = r.id
        LEFT JOIN NOTE_ENTREGA_DEVOLUCION   e  ON e.note_report_id  = r.id
        LEFT JOIN NOTE_PROVEEDOR            p  ON p.note_report_id  = r.id
        LEFT JOIN PROVIDER                  pv ON pv.id             = p.provider_id
        LEFT JOIN SEDE                       sd ON sd.id             = r.sede_id
        LEFT JOIN NOTE_ITEM                 i  ON i.note_id         = r.id
        LEFT JOIN NOTE_ITEM_ASSET           ia ON ia.item_id        = i.id
        LEFT JOIN NOTE_ITEM_COUNTABLE       ic ON ic.item_id        = i.id
        LEFT JOIN NOTE_ITEM_GLPI_TRACKING   ig ON ig.item_id         = i.id
        LEFT JOIN NOTE_ITEM_GLPI_RETURN_TRACKING igr ON igr.item_id  = i.id
        LEFT JOIN NOTE_ITEM_RETURN_TRACKING ir ON ir.item_id         = i.id
        LEFT JOIN (
            SELECT item_id,
                   SUM(CASE WHEN status = 'RETURNED' THEN quantity ELSE 0 END) AS returned_qty,
                   SUM(CASE WHEN status = 'LOST'     THEN quantity ELSE 0 END) AS lost_qty
            FROM NOTE_ITEM_RETURN_ALLOCATION
            GROUP BY item_id
        ) alloc ON alloc.item_id = i.id
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
                boolean needsReturnTracking = isPrestamo(report.getProfileType())
                    || isProviderReturnable(report.getProfileType(), report.getMotivo());
                insertItems(c, reportId, report.getItems(), needsReturnTracking);
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
            "INSERT INTO NOTE_REPORT (created_at, profile_type, technician_name, technician_dni, observations, sede_id, approval_status) VALUES (?, ?, ?, ?, ?, ?, ?)",
            PreparedStatement.RETURN_GENERATED_KEYS);
        ps.setString(1, report.getCreatedAt().toString());
        ps.setString(2, report.getProfileType());
        ps.setString(3, report.getAuthorName());
        ps.setString(4, report.getAuthorDni());
        ps.setString(5, report.getObservations());
        ps.setInt(6, report.getSedeId());
        ps.setString(7, report.getApprovalStatus());
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
                    (note_report_id, user_name, user_dni, user_email, motivo)
                VALUES (?, ?, ?, ?, ?)
                """);
            ps.setInt(1, reportId);
            ps.setString(2, report.getUserName());
            ps.setString(3, report.getUserDni());
            ps.setString(4, report.getUserEmail());
            ps.setString(5, report.getMotivo());
            ps.executeUpdate();

            // failure_cause/failure_details and area_evento each live in their own subtype
            // table now — a row exists only when that dimension actually applies, instead of
            // every ENTREGA_DEVOLUCION row always
            // carrying both regardless of profile type/motivo.
            if (report.getFailureCause() != null && !report.getFailureCause().isBlank()) {
                PreparedStatement falla = c.prepareStatement("""
                    INSERT INTO NOTE_DEVOLUCION_FALLA (note_report_id, failure_cause, failure_details)
                    VALUES (?, ?, ?)
                    """);
                falla.setInt(1, reportId);
                falla.setString(2, report.getFailureCause());
                falla.setString(3, report.getFailureDetails());
                falla.executeUpdate();
            }
            if (report.getAreaEvento() != null && !report.getAreaEvento().isBlank()) {
                PreparedStatement areaEvento = c.prepareStatement("""
                    INSERT INTO NOTE_PRESTAMO_AREA_EVENTO (note_report_id, area_evento)
                    VALUES (?, ?)
                    """);
                areaEvento.setInt(1, reportId);
                areaEvento.setString(2, report.getAreaEvento());
                areaEvento.executeUpdate();
            }
        }
    }

    // The base NOTE_ITEM insert can't be batched like the old single-table insert was — each
    // subtype-table row needs its own base row's generated id first. Only the 4 subtype
    // PreparedStatements below are batched. A subtype row is only ever inserted when that
    // dimension actually applies (see the 5-table split in DatabaseService/RemoteDatabaseService)
    // — trusting the exact same caller-supplied status values this method already trusted before
    // the split, not re-deriving the applicability rule a second time here.
    private void insertItems(Connection c, int reportId, List<NoteReportItem> items, boolean needsReturnTracking) throws SQLException {
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

        // Every item on a Préstamo note (asset or countable alike) needs its own return tracked;
        // same for a Provider note whose Motivo is in the config-driven returnable list (e.g.
        // Garantía, Reparación — see isProviderReturnable()). Every other note type's items are
        // simply not applicable.
        ReturnStatus returnStatus = needsReturnTracking ? ReturnStatus.PENDING : ReturnStatus.N_A;
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
        appendIn(sql, params, "r.approval_status", filter.getApprovalStatuses());
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
    public List<NoteReport> getPendingApproval() {
        return getFiltered(HistoryFilter.pendingApproval());
    }

    // approval_status is a plain UPDATE on the always-existing NOTE_REPORT row — but
    // rejection_reason (split into NOTE_REPORT_REJECTION, a row exists only for an
    // actually-rejected note) needs the same check-then-insert-or-update-or-delete shape as
    // updateItemGlpiStatus()/updateItemReturnStatus() below: a blank reason (approving, or
    // re-approving a previously-rejected note) deletes any existing row; a real reason
    // upserts one.
    @Override
    public void updateNoteApprovalStatus(int reportId, String status, String rejectionReason) {
        try (Connection c = connector.get()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE NOTE_REPORT SET approval_status = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setInt(2, reportId);
                ps.executeUpdate();
            }
            if (rejectionReason == null || rejectionReason.isBlank()) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM NOTE_REPORT_REJECTION WHERE note_report_id = ?")) {
                    del.setInt(1, reportId);
                    del.executeUpdate();
                }
            } else {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE NOTE_REPORT_REJECTION SET rejection_reason = ? WHERE note_report_id = ?")) {
                    up.setString(1, rejectionReason);
                    up.setInt(2, reportId);
                    if (up.executeUpdate() == 0) {
                        try (PreparedStatement ins = c.prepareStatement(
                                "INSERT INTO NOTE_REPORT_REJECTION (note_report_id, rejection_reason) VALUES (?, ?)")) {
                            ins.setInt(1, reportId);
                            ins.setString(2, rejectionReason);
                            ins.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update note approval status", e);
        }
    }

    @Override
    public List<String> getDistinctItemTypes() {
        return queryDistinct("""
            SELECT DISTINCT t.name FROM NOTE_ITEM ni JOIN TYPE t ON t.id = ni.type_id ORDER BY t.name
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

    // created_at is stored as ISO-8601 text on SQLite (TEXT column, unchanged) but as a real
    // DATETIME2 on the remote SQL Server connection (this same class runs against both — see
    // ServiceLocator) — DATETIME2's own getString() rendering is space-separated
    // ("yyyy-MM-dd HH:mm:ss[.fffffff]"), not 'T'-separated, so tolerate both instead of assuming
    // SQLite's format everywhere.
    private static LocalDateTime parseStoredTimestamp(String raw) {
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException isoFailed) {
            int spaceIdx = raw.indexOf(' ');
            if (spaceIdx < 0) throw isoFailed;
            return LocalDateTime.parse(raw.substring(0, spaceIdx) + "T" + raw.substring(spaceIdx + 1));
        }
    }

    // Same space-vs-'T' tolerance as parseStoredTimestamp(), but for the two tracking-table
    // columns that are kept as raw display strings (never parsed to LocalDateTime) — normalizes
    // to 'T'-separated so NoteDetailController/PrestamoDetailController's
    // getXxxUpdatedAt().substring(0, 16) display trick keeps working regardless of which engine
    // produced the value.
    private static String normalizeTimestampString(String raw) {
        if (raw == null) return null;
        int spaceIdx = raw.indexOf(' ');
        return spaceIdx < 0 ? raw : raw.substring(0, spaceIdx) + "T" + raw.substring(spaceIdx + 1);
    }

    private NoteReport mapSummary(ResultSet rs) throws SQLException {
        NoteReport r = new NoteReport();
        r.setId(rs.getInt("id"));
        r.setCreatedAt(parseStoredTimestamp(rs.getString("created_at")));
        r.setProfileType(rs.getString("profile_type"));
        r.setApprovalStatus(rs.getString("approval_status"));
        r.setRejectionReason(rs.getString("rejection_reason"));
        r.setAuthorName(rs.getString("author_name"));
        r.setAuthorDni(rs.getString("author_dni"));
        r.setRecipientDisplay(rs.getString("recipient"));
        r.setMotivo(rs.getString("motivo"));
        r.setSede(rs.getString("sede"));
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
                   r.approval_status, rr.rejection_reason,
                   r.technician_name AS author_name,
                   r.technician_dni AS author_dni,
                   COALESCE(r.observations, '') AS observations,
                   COALESCE(sd.name, '') AS sede,
                   COALESCE(r.sede_id, 0) AS sede_id,
                   COALESCE(e.user_name, '')    AS user_name,
                   COALESCE(e.user_dni, '')     AS user_dni,
                   COALESCE(e.user_email, '')   AS user_email,
                   COALESCE(e.motivo, p.motivo, '') AS motivo,
                   COALESCE(fd.failure_cause, '')   AS failure_cause,
                   COALESCE(fd.failure_details, '') AS failure_details,
                   COALESCE(ae.area_evento, '')     AS area_evento,
                   COALESCE(pv.name, '') AS provider_name,
                   COALESCE(p.provider_id, 0) AS provider_id,
                   COALESCE(p.cuit, '')          AS cuit,
                   COALESCE(p.responsible_name, '') AS responsible_name,
                   COALESCE(p.responsible_dni, '')  AS responsible_dni
            FROM NOTE_REPORT r
            LEFT JOIN NOTE_REPORT_REJECTION    rr ON rr.note_report_id = r.id
            LEFT JOIN NOTE_ENTREGA_DEVOLUCION  e  ON e.note_report_id  = r.id
            LEFT JOIN NOTE_DEVOLUCION_FALLA    fd ON fd.note_report_id = r.id
            LEFT JOIN NOTE_PRESTAMO_AREA_EVENTO ae ON ae.note_report_id = r.id
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
            r.setCreatedAt(parseStoredTimestamp(rs.getString("created_at")));
            r.setProfileType(rs.getString("profile_type"));
            r.setApprovalStatus(rs.getString("approval_status"));
            r.setRejectionReason(rs.getString("rejection_reason"));
            r.setAuthorName(rs.getString("author_name"));
            r.setAuthorDni(rs.getString("author_dni"));
            r.setObservations(rs.getString("observations"));
            r.setSede(rs.getString("sede"));
            r.setSedeId(rs.getInt("sede_id"));
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
                   COALESCE(gr.status, 'N_A') AS glpi_return_status,
                   gr.rejection_reason AS glpi_return_rejection_reason,
                   gr.status_updated_at AS glpi_return_status_updated_at,
                   COALESCE(rt.status, 'N_A') AS return_status,
                   rt.rejection_reason AS return_rejection_reason,
                   rt.status_updated_at AS return_status_updated_at,
                   COALESCE(alloc.returned_qty, 0) AS returned_quantity,
                   COALESCE(alloc.lost_qty, 0) AS lost_quantity
            FROM NOTE_ITEM b
            JOIN TYPE t ON t.id = b.type_id
            JOIN BRAND br ON br.id = b.brand_id
            JOIN MODEL m ON m.id = b.model_id
            LEFT JOIN NOTE_ITEM_ASSET           a  ON a.item_id  = b.id
            LEFT JOIN NOTE_ITEM_COUNTABLE       ct ON ct.item_id = b.id
            LEFT JOIN NOTE_ITEM_GLPI_TRACKING   g  ON g.item_id  = b.id
            LEFT JOIN NOTE_ITEM_GLPI_RETURN_TRACKING gr ON gr.item_id = b.id
            LEFT JOIN NOTE_ITEM_RETURN_TRACKING rt ON rt.item_id = b.id
            LEFT JOIN (
                SELECT item_id,
                       SUM(CASE WHEN status = 'RETURNED' THEN quantity ELSE 0 END) AS returned_qty,
                       SUM(CASE WHEN status = 'LOST'     THEN quantity ELSE 0 END) AS lost_qty
                FROM NOTE_ITEM_RETURN_ALLOCATION
                GROUP BY item_id
            ) alloc ON alloc.item_id = b.id
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
            item.setGlpiStatusUpdatedAt(normalizeTimestampString(rs.getString("glpi_status_updated_at")));
            item.setGlpiReturnStatus(GlpiStatus.fromString(rs.getString("glpi_return_status")));
            item.setGlpiReturnRejectionReason(rs.getString("glpi_return_rejection_reason"));
            item.setGlpiReturnStatusUpdatedAt(normalizeTimestampString(rs.getString("glpi_return_status_updated_at")));
            item.setReturnStatus(ReturnStatus.fromString(rs.getString("return_status")));
            item.setReturnRejectionReason(rs.getString("return_rejection_reason"));
            item.setReturnStatusUpdatedAt(normalizeTimestampString(rs.getString("return_status_updated_at")));
            item.setReturnedQuantity(rs.getInt("returned_quantity"));
            item.setLostQuantity(rs.getInt("lost_quantity"));
            if (!item.isAsset()) {
                if (item.getLostQuantity() > 0) {
                    item.setLostBatches(loadAllocationBatches(c, item.getId(), ReturnStatus.LOST));
                }
                if (item.getReturnedQuantity() > 0) {
                    item.setReturnedBatches(loadAllocationBatches(c, item.getId(), ReturnStatus.RETURNED));
                }
            }
            items.add(item);
        }
        return items;
    }

    // Each allocation batch (LOST or RETURNED) is one real event with its own single timestamp
    // (LOST also has its own reason) — a one-query-per-countable-item follow-up, same accepted
    // "extra query per row, scoped to a single detail view, not a bulk list" trade-off already
    // used elsewhere in this class (see HistoryController.exportRowValues() in CLAUDE.md for the
    // same precedent).
    private List<ReturnAllocationBatch> loadAllocationBatches(Connection c, int itemId, ReturnStatus status) throws SQLException {
        List<ReturnAllocationBatch> batches = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT quantity, reason, updated_at FROM NOTE_ITEM_RETURN_ALLOCATION
                WHERE item_id = ? AND status = ? ORDER BY updated_at ASC
                """)) {
            ps.setInt(1, itemId);
            ps.setString(2, status.toDbString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    batches.add(new ReturnAllocationBatch(
                        rs.getInt("quantity"),
                        rs.getString("reason"),
                        normalizeTimestampString(rs.getString("updated_at"))));
                }
            }
        }
        return batches;
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
        // Plain UPDATE-then-INSERT-if-missing, not an ON CONFLICT upsert (that was SQLite/
        // PostgreSQL-only syntax — SQL Server has no ON CONFLICT/MERGE-free upsert clause at all,
        // and this class runs unchanged against both the local SQLite and remote SQL Server
        // connections). Mirrors the same shape already established for every other upsert in this
        // codebase — see "Remote SQL Server" / "Upserts rewritten as plain check-then-insert/update"
        // in CLAUDE.md. Defensive either way: every real caller only ever transitions an item that
        // already has a NOTE_ITEM_GLPI_TRACKING row (PENDING), but the INSERT fallback is correct
        // even if that assumption is ever wrong, at no extra cost.
        try (Connection c = connector.get()) {
            String updatedAt = LocalDateTime.now().toString();
            try (PreparedStatement up = c.prepareStatement("""
                    UPDATE NOTE_ITEM_GLPI_TRACKING
                    SET status = ?, rejection_reason = ?, status_updated_at = ?
                    WHERE item_id = ?
                    """)) {
                up.setString(1, status.toDbString());
                up.setString(2, reason);
                up.setString(3, updatedAt);
                up.setInt(4, itemId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
                            VALUES (?, ?, ?, ?)
                            """)) {
                        ins.setInt(1, itemId);
                        ins.setString(2, status.toDbString());
                        ins.setString(3, reason);
                        ins.setString(4, updatedAt);
                        ins.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item GLPI status", e);
        }
    }

    // ── GLPI "synced back in" status update (second, independent dimension) ───

    @Override
    public void updateItemGlpiReturnStatus(int itemId, GlpiStatus status, String reason) {
        // Same UPDATE-then-INSERT-if-missing shape as updateItemGlpiStatus() above. Unlike that
        // one, the very first call for a given item is expected to be an INSERT — this dimension
        // doesn't exist at all until NoteDetailController.handleProviderReceived() seeds a PENDING
        // row the moment the item's return is validated (see NOTE_ITEM_GLPI_RETURN_TRACKING's own
        // doc in DatabaseService).
        try (Connection c = connector.get()) {
            String updatedAt = LocalDateTime.now().toString();
            try (PreparedStatement up = c.prepareStatement("""
                    UPDATE NOTE_ITEM_GLPI_RETURN_TRACKING
                    SET status = ?, rejection_reason = ?, status_updated_at = ?
                    WHERE item_id = ?
                    """)) {
                up.setString(1, status.toDbString());
                up.setString(2, reason);
                up.setString(3, updatedAt);
                up.setInt(4, itemId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO NOTE_ITEM_GLPI_RETURN_TRACKING (item_id, status, rejection_reason, status_updated_at)
                            VALUES (?, ?, ?, ?)
                            """)) {
                        ins.setInt(1, itemId);
                        ins.setString(2, status.toDbString());
                        ins.setString(3, reason);
                        ins.setString(4, updatedAt);
                        ins.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item GLPI return status", e);
        }
    }

    // ── Préstamo return status update ─────────────────────────────────────────

    @Override
    public void updateItemReturnStatus(int itemId, ReturnStatus status, String reason) {
        // Same UPDATE-then-INSERT-if-missing shape as updateItemGlpiStatus() above, for the same
        // SQL Server compatibility reason.
        try (Connection c = connector.get()) {
            String updatedAt = LocalDateTime.now().toString();
            try (PreparedStatement up = c.prepareStatement("""
                    UPDATE NOTE_ITEM_RETURN_TRACKING
                    SET status = ?, rejection_reason = ?, status_updated_at = ?
                    WHERE item_id = ?
                    """)) {
                up.setString(1, status.toDbString());
                up.setString(2, reason);
                up.setString(3, updatedAt);
                up.setInt(4, itemId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO NOTE_ITEM_RETURN_TRACKING (item_id, status, rejection_reason, status_updated_at)
                            VALUES (?, ?, ?, ?)
                            """)) {
                        ins.setInt(1, itemId);
                        ins.setString(2, status.toDbString());
                        ins.setString(3, reason);
                        ins.setString(4, updatedAt);
                        ins.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item return status", e);
        }
    }

    // ── Countable partial return/lost allocation ──────────────────────────────

    @Override
    public void allocateCountableReturn(int itemId, ReturnStatus status, int quantity, String reason) {
        if (status != ReturnStatus.RETURNED && status != ReturnStatus.LOST) {
            throw new IllegalArgumentException("El estado de la asignación debe ser Devuelto o Perdido");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad a asignar debe ser mayor a cero");
        }
        try (Connection c = connector.get()) {
            int pending = pendingCountableQuantity(c, itemId);
            if (quantity > pending) {
                throw new IllegalArgumentException(
                    "La cantidad a asignar (" + quantity + ") supera la cantidad pendiente (" + pending + ")");
            }
            try (PreparedStatement ins = c.prepareStatement("""
                    INSERT INTO NOTE_ITEM_RETURN_ALLOCATION (item_id, status, quantity, reason, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                ins.setInt(1, itemId);
                ins.setString(2, status.toDbString());
                ins.setInt(3, quantity);
                ins.setString(4, reason);
                ins.setString(5, LocalDateTime.now().toString());
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to allocate countable return", e);
        }
    }

    private int pendingCountableQuantity(Connection c, int itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT ct.quantity AS qty,
                       COALESCE((SELECT SUM(quantity) FROM NOTE_ITEM_RETURN_ALLOCATION WHERE item_id = ?), 0) AS allocated
                FROM NOTE_ITEM_COUNTABLE ct
                WHERE ct.item_id = ?
                """)) {
            ps.setInt(1, itemId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0;
                return rs.getInt("qty") - rs.getInt("allocated");
            }
        }
    }

}
