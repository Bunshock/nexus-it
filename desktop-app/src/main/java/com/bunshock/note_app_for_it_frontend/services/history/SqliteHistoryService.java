package com.bunshock.note_app_for_it_frontend.services.history;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.history.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnAllocationBatch;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnStatus;

import com.bunshock.note_app_for_it_frontend.services.catalog.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.core.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
public class SqliteHistoryService implements IHistoryService {

    // profile_type is stored raw, so casing/accents vary by how the row was created.
    private static final List<String> PRESTAMO_PROFILE_TYPES = List.of("PRÉSTAMO", "PRESTAMO", "Préstamo");

    private static boolean isPrestamo(String profileType) {
        return profileType != null && PRESTAMO_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase);
    }


    // return_pending/returned/lost counts are weighted by quantity, not row count: an asset row
    // is always 1, a countable row splits across RETURNED/LOST via the pre-aggregated `alloc`
    // subquery (one row per item_id, so it can't fan out the surrounding SUMs). The CASE-based
    // clamp replaces a 2-arg MAX(), which SQL Server doesn't have.
    //
    // pending/synced/rejected counts use COALESCE(igr.status, ig.status): once an item's return
    // is validated and a GLPI_RETURN row exists, that becomes its effective GLPI status (what
    // matters after a return is whether GLPI reflects the item being back, not the original
    // sync-out). igr only has rows for a returnable Provider note post-validation, so this is a
    // no-op for every other note/item.
    private static final String LIST_BASE_SQL = """
        SELECT r.id, r.created_at, r.profile_type,
               r.approval_status, rr.rejection_reason,
               r.technician_name AS author_name,
               r.technician_dni AS author_dni,
               COALESCE(e.user_name, pv.name, ssi.destination_label, rmo.destination_label, '') AS recipient,
               CASE WHEN r.profile_type = 'REMITO DE ENVÍO' THEN 'Envío'
                    ELSE COALESCE(e.motivo, p.motivo, '') END AS motivo,
               COALESCE(sd.name, '') AS sede,
               COALESCE(ssi.destination_label, rmo.destination_label, '') AS destination_label,
               COALESCE(ssi.address, rmo.address, '') AS remito_address,
               COALESCE(ssi.recipients, rmo.recipients, '') AS remito_recipients,
               SUM(CASE WHEN COALESCE(igr.status, ig.status) = 'PENDING'  THEN 1 ELSE 0 END) AS pending_count,
               SUM(CASE WHEN COALESCE(igr.status, ig.status) = 'SYNCED'   THEN 1 ELSE 0 END) AS synced_count,
               SUM(CASE WHEN COALESCE(igr.status, ig.status) = 'REJECTED' THEN 1 ELSE 0 END) AS rejected_count,
               SUM(CASE WHEN ia.item_id IS NOT NULL THEN 1 ELSE 0 END) AS asset_count,
               SUM(CASE WHEN ia.item_id IS NULL THEN 1 ELSE 0 END) AS countable_count,
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
        LEFT JOIN NOTE_REMITO_SEDE            rms ON rms.note_report_id = r.id
        LEFT JOIN SEDE_SHIPPING_INFO          ssi ON ssi.id             = rms.shipping_info_id
        LEFT JOIN NOTE_REMITO_OTHER           rmo ON rmo.note_report_id = r.id
        LEFT JOIN NOTE_ITEM                 i  ON i.note_id         = r.id
        LEFT JOIN NOTE_ITEM_ASSET           ia ON ia.item_id        = i.id
        LEFT JOIN NOTE_ITEM_COUNTABLE       ic ON ic.item_id        = i.id
        LEFT JOIN NOTE_ITEM_STATUS_TRACKING ig  ON ig.item_id  = i.id AND ig.tracking_type  = 'GLPI'
        LEFT JOIN NOTE_ITEM_STATUS_TRACKING igr ON igr.item_id = i.id AND igr.tracking_type = 'GLPI_RETURN'
        LEFT JOIN NOTE_ITEM_STATUS_TRACKING ir  ON ir.item_id  = i.id AND ir.tracking_type  = 'RETURN'
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

    public SqliteHistoryService(Supplier<Connection> connector) {
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
                    || IHistoryService.isProviderReturnable(report.getProfileType(), report.getMotivo());
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
        if (report.getDestinationLabel() != null) {
            // Discriminated by shippingInfoId, not destinationSedeId — a catalog-Sede destination
            // always has a real SEDE_SHIPPING_INFO row to reference.
            if (report.getShippingInfoId() != null) {
                PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO NOTE_REMITO_SEDE (note_report_id, shipping_info_id)
                    VALUES (?, ?)
                    """);
                ps.setInt(1, reportId);
                ps.setInt(2, report.getShippingInfoId());
                ps.executeUpdate();
            } else {
                PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO NOTE_REMITO_OTHER (note_report_id, destination_label, address, recipients)
                    VALUES (?, ?, ?, ?)
                    """);
                ps.setInt(1, reportId);
                ps.setString(2, report.getDestinationLabel());
                ps.setString(3, report.getAddress());
                ps.setString(4, report.getRecipients());
                ps.executeUpdate();
            }
        } else if (report.getProviderName() != null) {
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
            // table — a row exists only when that dimension actually applies.
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

    // The base NOTE_ITEM insert can't be batched — each subtype-table row needs its own base
    // row's generated id first. Only the 4 subtype PreparedStatements below are batched. A
    // subtype row is only inserted when that dimension actually applies.
    private void insertItems(Connection c, int reportId, List<NoteReportItem> items, boolean needsReturnTracking) throws SQLException {
        if (items == null) return;
        PreparedStatement itemPs = c.prepareStatement("""
            INSERT INTO NOTE_ITEM (note_id, type_id, brand_id, model_id, observations, modifies_stock)
            VALUES (?, ?, ?, ?, ?, ?)
            """, PreparedStatement.RETURN_GENERATED_KEYS);
        PreparedStatement assetPs = c.prepareStatement(
            "INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f) VALUES (?, ?, ?)");
        PreparedStatement countablePs = c.prepareStatement(
            "INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity) VALUES (?, ?)");
        PreparedStatement glpiPs = c.prepareStatement("""
            INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
            VALUES (?, 'GLPI', ?, ?, ?)
            """);
        PreparedStatement returnPs = c.prepareStatement("""
            INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
            VALUES (?, 'RETURN', ?, ?, ?)
            """);
        // Row only inserted for an item flagged "no modifica stock" with a real reason — row
        // absence means the exception doesn't apply, same pattern as glpiPs/returnPs above.
        PreparedStatement stockExceptionPs = c.prepareStatement(
            "INSERT INTO NOTE_ITEM_STOCK_EXCEPTION (item_id, reason) VALUES (?, ?)");

        // Return tracking applies to every item (asset and countable alike, unlike GLPI) on a
        // Préstamo note, and on a Provider note whose Motivo is in the returnable list.
        for (NoteReportItem item : items) {
            itemPs.setInt(1, reportId);
            itemPs.setInt(2, item.getTypeId());
            itemPs.setInt(3, item.getBrandId());
            itemPs.setInt(4, item.getModelId());
            itemPs.setString(5, item.getObservations());
            itemPs.setInt(6, item.isModifiesStock() ? 1 : 0);
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
            ReturnStatus returnStatus = needsReturnTracking ? ReturnStatus.PENDING : ReturnStatus.N_A;
            if (returnStatus != ReturnStatus.N_A) {
                returnPs.setInt(1, itemId);
                returnPs.setString(2, returnStatus.toDbString());
                returnPs.setString(3, null);
                returnPs.setString(4, null);
                returnPs.addBatch();
            }
            if (!item.isModifiesStock() && item.getModifiesStockReason() != null
                    && !item.getModifiesStockReason().isBlank()) {
                stockExceptionPs.setInt(1, itemId);
                stockExceptionPs.setString(2, item.getModifiesStockReason());
                stockExceptionPs.addBatch();
            }
        }
        assetPs.executeBatch();
        countablePs.executeBatch();
        glpiPs.executeBatch();
        returnPs.executeBatch();
        stockExceptionPs.executeBatch();
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
            sql.append(" AND COALESCE(e.user_name, pv.name, ssi.destination_label, rmo.destination_label, '') LIKE ?");
            params.add("%" + filter.getRecipientSearch().trim() + "%");
        }
        if (filter.getAuthorSearch() != null && !filter.getAuthorSearch().isBlank()) {
            // Can't reference the "author_name" SELECT alias — SQLite tolerates it, SQL Server
            // doesn't, and this query runs against both. Repeat the column instead.
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

    // Matches regardless of deprecated status, so filtering by a name a type/brand/model was
    // later renamed away from still finds the notes that used it at the time.
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
            // Not asset count == 0 — a Préstamo note's assets are all glpi_status N_A, so a raw
            // asset count would wrongly exclude such a note from the "Sin GLPI" filter.
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

    // rejection_reason lives in NOTE_REPORT_REJECTION (a row exists only for a rejected note) —
    // a blank reason deletes any existing row, a real reason upserts one.
    @Override
    public void updateNoteApprovalStatus(int reportId, String status, String rejectionReason) {
        // Applied before the approval_status write — a failed stock adjustment must not leave
        // the note APPROVED with the stock unmoved.
        if ("APPROVED".equals(status)) {
            applyNoteStockIfNeeded(reportId);
        }
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

    private record StockItemKey(int modelId, int brandId, int typeId) {}

    // Egress (stock decreases): Entrega/Entrega Permanente/Préstamo/Provider. Ingress (stock
    // increases): Devolución. Remito has its own dual-Sede move below. Case/legacy-variant
    // tolerant — a pre-rename installation's rows still say "FIN DE CONTRATO".
    private static final List<String> EGRESS_PROFILE_TYPES = List.of(
        "ENTREGA", "ENTREGA PERMANENTE", "FIN DE CONTRATO", "PRÉSTAMO", "PRESTAMO", "ENTREGA - PROVEEDOR");
    private static final List<String> INGRESS_PROFILE_TYPES = List.of("DEVOLUCIÓN", "DEVOLUCION");

    // Stock dispatcher for every note type, called from updateNoteApprovalStatus() on approval.
    // No-op for a note with no stock effect or already applied (stock_applied = 1).
    private void applyNoteStockIfNeeded(int reportId) {
        try (Connection c = connector.get()) {
            String profileType;
            int sedeId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT profile_type, sede_id, stock_applied FROM NOTE_REPORT WHERE id = ?")) {
                ps.setInt(1, reportId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next() || rs.getInt("stock_applied") != 0) return;
                profileType = rs.getString("profile_type");
                sedeId = rs.getInt("sede_id");
            }

            boolean applied;
            if ("REMITO DE ENVÍO".equalsIgnoreCase(profileType)) {
                applied = applyRemitoStock(c, reportId, sedeId);
            } else if (EGRESS_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase)) {
                applied = applyDirectionalStock(c, reportId, sedeId, -1,
                    "Stock insuficiente en la sede para aprobar esta nota");
            } else if (INGRESS_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase)) {
                applied = applyDirectionalStock(c, reportId, sedeId, 1, null);
            } else {
                applied = false;
            }

            if (applied) {
                try (PreparedStatement mark = c.prepareStatement(
                        "UPDATE NOTE_REPORT SET stock_applied = 1 WHERE id = ?")) {
                    mark.setInt(1, reportId);
                    mark.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to apply note stock adjustment", e);
        }
    }

    // Aggregates by (model, brand, type) first, since the same model can appear on multiple item
    // rows and only their combined total needs to fit in the Sede's stock. On egress, every
    // aggregate is checked against current stock before any write, so an over-requested model
    // can't leave a different model's stock already moved. Not cross-connection-transactional —
    // an accepted limitation, since approval is a low-concurrency, single-admin action in
    // practice. Returns whether anything was actually moved (false for a note with zero items).
    private boolean applyDirectionalStock(Connection c, int reportId, int sedeId, int direction,
            String insufficientMessage) throws SQLException {
        java.util.Map<StockItemKey, Integer> quantities = aggregateItemQuantities(c, reportId);
        if (quantities.isEmpty()) return false;

        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        if (insufficientMessage != null) {
            for (var entry : quantities.entrySet()) {
                StockItemKey key = entry.getKey();
                int available = equipment.getModelStock(key.modelId(), key.brandId(), key.typeId(), sedeId);
                if (available < entry.getValue()) {
                    throw new IllegalArgumentException(insufficientMessage);
                }
            }
        }
        for (var entry : quantities.entrySet()) {
            StockItemKey key = entry.getKey();
            equipment.adjustModelStock(key.modelId(), key.brandId(), key.typeId(), sedeId,
                direction * entry.getValue());
        }
        return true;
    }

    // Remito's own dual-Sede move — source always decrements, destination only increments when
    // it's a real catalog Sede (a custom/manual destination has no stock to receive).
    private boolean applyRemitoStock(Connection c, int reportId, int sourceSedeId) throws SQLException {
        // Absence from NOTE_REMITO_SEDE means a custom/manual destination (in NOTE_REMITO_OTHER
        // instead) — a valid, expected case, not an error.
        Integer destinationSedeId = null;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT ssi.sede_id FROM NOTE_REMITO_SEDE rms
                JOIN SEDE_SHIPPING_INFO ssi ON ssi.id = rms.shipping_info_id
                WHERE rms.note_report_id = ?
                """)) {
            ps.setInt(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) destinationSedeId = rs.getInt("sede_id");
            }
        }

        java.util.Map<StockItemKey, Integer> quantities = aggregateItemQuantities(c, reportId);
        if (quantities.isEmpty()) return false;

        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        for (var entry : quantities.entrySet()) {
            StockItemKey key = entry.getKey();
            int available = equipment.getModelStock(key.modelId(), key.brandId(), key.typeId(), sourceSedeId);
            if (available < entry.getValue()) {
                throw new IllegalArgumentException(
                    "Stock insuficiente en la sede de origen para aprobar este remito");
            }
        }
        for (var entry : quantities.entrySet()) {
            StockItemKey key = entry.getKey();
            equipment.adjustModelStock(key.modelId(), key.brandId(), key.typeId(), sourceSedeId, -entry.getValue());
            if (destinationSedeId != null) {
                equipment.adjustModelStock(key.modelId(), key.brandId(), key.typeId(), destinationSedeId, entry.getValue());
            }
        }
        return true;
    }

    private java.util.Map<StockItemKey, Integer> aggregateItemQuantities(Connection c, int reportId) throws SQLException {
        java.util.Map<StockItemKey, Integer> quantities = new java.util.LinkedHashMap<>();
        for (NoteReportItem item : loadItems(c, reportId)) {
            // An item marked "no modifica stock" is excluded entirely, regardless of direction.
            if (!item.isModifiesStock()) continue;
            StockItemKey key = new StockItemKey(item.getModelId(), item.getBrandId(), item.getTypeId());
            int qty = item.isAsset() ? 1 : item.getQuantity();
            quantities.merge(key, qty, Integer::sum);
        }
        return quantities;
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

    // Tolerates both SQLite's ISO-8601 ('T'-separated) text and SQL Server DATETIME2's
    // space-separated getString() rendering, since this class runs against both.
    private static LocalDateTime parseStoredTimestamp(String raw) {
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException isoFailed) {
            int spaceIdx = raw.indexOf(' ');
            if (spaceIdx < 0) throw isoFailed;
            return LocalDateTime.parse(raw.substring(0, spaceIdx) + "T" + raw.substring(spaceIdx + 1));
        }
    }

    // Same space-vs-'T' tolerance as parseStoredTimestamp(), but for columns kept as raw
    // display strings rather than parsed to LocalDateTime.
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
        r.setDestinationLabel(rs.getString("destination_label"));
        r.setAddress(rs.getString("remito_address"));
        r.setRecipients(rs.getString("remito_recipients"));
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
                   COALESCE(p.responsible_dni, '')  AS responsible_dni,
                   ssi.sede_id                                             AS destination_sede_id,
                   COALESCE(ssi.destination_label, rmo.destination_label)  AS destination_label,
                   COALESCE(ssi.address, rmo.address)                      AS remito_address,
                   COALESCE(ssi.recipients, rmo.recipients)                AS remito_recipients,
                   r.stock_applied        AS stock_applied
            FROM NOTE_REPORT r
            LEFT JOIN NOTE_REPORT_REJECTION    rr ON rr.note_report_id = r.id
            LEFT JOIN NOTE_ENTREGA_DEVOLUCION  e  ON e.note_report_id  = r.id
            LEFT JOIN NOTE_DEVOLUCION_FALLA    fd ON fd.note_report_id = r.id
            LEFT JOIN NOTE_PRESTAMO_AREA_EVENTO ae ON ae.note_report_id = r.id
            LEFT JOIN NOTE_PROVEEDOR           p  ON p.note_report_id  = r.id
            LEFT JOIN PROVIDER                 pv ON pv.id             = p.provider_id
            LEFT JOIN SEDE                     sd ON sd.id             = r.sede_id
            LEFT JOIN NOTE_REMITO_SEDE         rms ON rms.note_report_id = r.id
            LEFT JOIN SEDE_SHIPPING_INFO       ssi ON ssi.id             = rms.shipping_info_id
            LEFT JOIN NOTE_REMITO_OTHER        rmo ON rmo.note_report_id = r.id
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
            int destSedeId = rs.getInt("destination_sede_id");
            r.setDestinationSedeId(rs.wasNull() ? null : destSedeId);
            r.setDestinationLabel(rs.getString("destination_label"));
            r.setAddress(rs.getString("remito_address"));
            r.setRecipients(rs.getString("remito_recipients"));
            r.setStockApplied(rs.getInt("stock_applied") != 0);
            r.setItems(loadItems(c, id));
            return r;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load report " + id, e);
        }
    }

    // is_asset/glpi_status/etc. are computed via LEFT JOIN/COALESCE against the split subtype
    // tables, reproducing the flat column shape NoteReportItem expects.
    private List<NoteReportItem> loadItems(Connection c, int reportId) throws SQLException {
        List<NoteReportItem> items = new ArrayList<>();
        PreparedStatement ps = c.prepareStatement("""
            SELECT b.id, b.type_id, b.brand_id, b.model_id,
                   t.name AS type_name, br.name AS brand_name, m.name AS model_name,
                   b.observations, b.modifies_stock, se.reason AS modifies_stock_reason,
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
            LEFT JOIN NOTE_ITEM_STATUS_TRACKING g  ON g.item_id  = b.id AND g.tracking_type  = 'GLPI'
            LEFT JOIN NOTE_ITEM_STATUS_TRACKING gr ON gr.item_id = b.id AND gr.tracking_type = 'GLPI_RETURN'
            LEFT JOIN NOTE_ITEM_STATUS_TRACKING rt ON rt.item_id = b.id AND rt.tracking_type = 'RETURN'
            LEFT JOIN NOTE_ITEM_STOCK_EXCEPTION se ON se.item_id = b.id
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
            item.setModifiesStock(rs.getInt("modifies_stock") == 1);
            item.setModifiesStockReason(rs.getString("modifies_stock_reason"));
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

    // Each allocation batch (LOST or RETURNED) is one real event with its own timestamp — a
    // one-query-per-countable-item follow-up, scoped to a single detail view, not a bulk list.
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
        // Plain UPDATE-then-INSERT-if-missing, not ON CONFLICT — SQL Server has no equivalent
        // upsert clause, and this class runs against both SQLite and SQL Server.
        try (Connection c = connector.get()) {
            String updatedAt = LocalDateTime.now().toString();
            try (PreparedStatement up = c.prepareStatement("""
                    UPDATE NOTE_ITEM_STATUS_TRACKING
                    SET status = ?, rejection_reason = ?, status_updated_at = ?
                    WHERE item_id = ? AND tracking_type = 'GLPI'
                    """)) {
                up.setString(1, status.toDbString());
                up.setString(2, reason);
                up.setString(3, updatedAt);
                up.setInt(4, itemId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                            VALUES (?, 'GLPI', ?, ?, ?)
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
        // Same shape as updateItemGlpiStatus() above, but the first call for a given item is
        // expected to be an INSERT — this dimension doesn't exist until the item's return is
        // validated.
        try (Connection c = connector.get()) {
            String updatedAt = LocalDateTime.now().toString();
            try (PreparedStatement up = c.prepareStatement("""
                    UPDATE NOTE_ITEM_STATUS_TRACKING
                    SET status = ?, rejection_reason = ?, status_updated_at = ?
                    WHERE item_id = ? AND tracking_type = 'GLPI_RETURN'
                    """)) {
                up.setString(1, status.toDbString());
                up.setString(2, reason);
                up.setString(3, updatedAt);
                up.setInt(4, itemId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                            VALUES (?, 'GLPI_RETURN', ?, ?, ?)
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
        // Same UPDATE-then-INSERT-if-missing shape as updateItemGlpiStatus() above.
        try (Connection c = connector.get()) {
            String previousStatus = currentReturnStatus(c, itemId);
            String updatedAt = LocalDateTime.now().toString();
            try (PreparedStatement up = c.prepareStatement("""
                    UPDATE NOTE_ITEM_STATUS_TRACKING
                    SET status = ?, rejection_reason = ?, status_updated_at = ?
                    WHERE item_id = ? AND tracking_type = 'RETURN'
                    """)) {
                up.setString(1, status.toDbString());
                up.setString(2, reason);
                up.setString(3, updatedAt);
                up.setInt(4, itemId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                            VALUES (?, 'RETURN', ?, ?, ?)
                            """)) {
                        ins.setInt(1, itemId);
                        ins.setString(2, status.toDbString());
                        ins.setString(3, reason);
                        ins.setString(4, updatedAt);
                        ins.executeUpdate();
                    }
                }
            }
            // Only on a genuine transition into Returned — not an already-Returned re-call
            // (idempotency) and never for Lost. Whole-item, so always +1.
            if (status == ReturnStatus.RETURNED && !"RETURNED".equals(previousStatus)) {
                ItemStockInfo info = resolveItemStockInfo(c, itemId);
                if (info != null && info.modifiesStock()) {
                    ServiceLocator.getInstance().getEquipmentService()
                        .adjustModelStock(info.modelId(), info.brandId(), info.typeId(), info.sedeId(), 1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item return status", e);
        }
    }

    private String currentReturnStatus(Connection c, int itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT status FROM NOTE_ITEM_STATUS_TRACKING WHERE item_id = ? AND tracking_type = 'RETURN'")) {
            ps.setInt(1, itemId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("status") : null;
        }
    }

    private record ItemStockInfo(int modelId, int brandId, int typeId, int sedeId, boolean modifiesStock) {}

    // Credits back to the note's own sede_id — Préstamo/Provider equipment always returns to
    // where it left from. modifiesStock mirrors applyDirectionalStock()'s exceptional-item flag:
    // an item that never decremented stock must never credit it back in.
    private ItemStockInfo resolveItemStockInfo(Connection c, int itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT i.model_id, i.brand_id, i.type_id, r.sede_id, i.modifies_stock
                FROM NOTE_ITEM i JOIN NOTE_REPORT r ON r.id = i.note_id
                WHERE i.id = ?
                """)) {
            ps.setInt(1, itemId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new ItemStockInfo(rs.getInt("model_id"), rs.getInt("brand_id"), rs.getInt("type_id"),
                rs.getInt("sede_id"), rs.getInt("modifies_stock") == 1);
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
            // Unconditional credit — the pending-quantity check above already prevents
            // over-allocating, so the quantity itself is the idempotency guard. Never credit Lost.
            if (status == ReturnStatus.RETURNED) {
                ItemStockInfo info = resolveItemStockInfo(c, itemId);
                if (info != null && info.modifiesStock()) {
                    ServiceLocator.getInstance().getEquipmentService()
                        .adjustModelStock(info.modelId(), info.brandId(), info.typeId(), info.sedeId(), quantity);
                }
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
