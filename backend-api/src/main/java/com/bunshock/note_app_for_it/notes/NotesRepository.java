package com.bunshock.note_app_for_it.notes;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.catalog.CatalogRepository;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.config.ConfigRepository;
import com.bunshock.note_app_for_it.notes.dto.CreateNoteRequest;
import com.bunshock.note_app_for_it.notes.dto.NoteDetailResponse;
import com.bunshock.note_app_for_it.notes.dto.NoteItemRequest;
import com.bunshock.note_app_for_it.notes.dto.NoteItemResponse;
import com.bunshock.note_app_for_it.notes.dto.NoteSummaryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Ported from the desktop app's {@code SqliteHistoryService} (1165 lines — this is a v1 subset,
 * see the class-level TODOs below for what's deliberately not here yet). Uses the SAME
 * {@code NOTE_ITEM_STATUS_TRACKING} single-table-with-discriminator shape the real schema already
 * settled on (not the older 3-separate-tables shape some project docs still describe) — verified
 * against {@code database/sqlserver/01-schema.sql} directly, not against prose.
 *
 * <p>Approval-time stock movement (§3.4/§3.3 in the OLD, pre-redesign sense — a real stored
 * {@code MODEL_STOCK} counter moved at approval, not derived "Model Y") is IN SCOPE for v1, per
 * the approved plan's "restore old MODEL_STOCK + manual-flag behavior" decision.
 *
 * <p>The {@code GLPI_RETURN} tracking dimension (re-sync into GLPI after a returnable Provider
 * note's asset comes back — GLPI sync is one-way/no-revert, so the return is a separate flag,
 * not a revert of the original) is seeded to {@code PENDING} the moment such an asset's
 * {@code RETURN} step reaches {@code RETURNED}, and driven from there by
 * {@code /items/{id}/sync-return} + {@code /reject-sync-return}. Its status supersedes the
 * original {@code GLPI} status in the history summary counts once it exists.
 */
@Repository
public class NotesRepository {

    private static final List<String> PRESTAMO_PROFILE_TYPES = List.of("PRÉSTAMO", "PRESTAMO", "Préstamo");
    private static final List<String> REMITO_PROFILE_TYPES = List.of("REMITO DE ENVÍO", "REMITO DE ENVIO");

    private static final List<String> EGRESS_PROFILE_TYPES = List.of(
            "ENTREGA", "ENTREGA PERMANENTE", "FIN DE CONTRATO", "PRÉSTAMO", "PRESTAMO", "ENTREGA - PROVEEDOR");
    private static final List<String> INGRESS_PROFILE_TYPES = List.of("DEVOLUCIÓN", "DEVOLUCION");

    private static boolean isPrestamo(String profileType) {
        return profileType != null && PRESTAMO_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase);
    }

    private static boolean isRemito(String profileType) {
        return profileType != null && REMITO_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase);
    }

    // Reads live from ConfigRepository (APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO) now — was a
    // hardcoded constant before the Config module existed.
    private boolean isProviderReturnable(String profileType, String motivo) {
        if (!"ENTREGA - PROVEEDOR".equalsIgnoreCase(profileType) || motivo == null) {
            return false;
        }
        return config.getReturnableProviderMotivos().stream().anyMatch(motivo::equalsIgnoreCase);
    }

    private static final String LIST_BASE_SQL = """
            SELECT r.id, r.created_at, r.profile_type, r.sede_id,
                   r.approval_status, rr.rejection_reason,
                   r.technician_name AS author_name,
                   r.technician_dni AS author_dni,
                   COALESCE(e.user_name, pv.name, rssi.destination_label, rmo.destination_label, '') AS recipient,
                   COALESCE(e.motivo, p.motivo, '') AS motivo,
                   COALESCE(sd.name, '') AS sede,
                   COALESCE(rssi.destination_label, rmo.destination_label, '') AS destination_label,
                   COALESCE(rssi.address, rmo.address, '') AS destination_address,
                   COALESCE(rssi.recipients, rmo.recipients, '') AS destination_recipients,
                   -- effective GLPI status: once a returnable Provider asset's return is
                   -- validated, its GLPI_RETURN row supersedes the original sync-out (what
                   -- matters after a return is whether GLPI reflects the item being back)
                   SUM(CASE WHEN COALESCE(gr.status, g.status) = 'PENDING'  THEN 1 ELSE 0 END) AS pending_count,
                   SUM(CASE WHEN COALESCE(gr.status, g.status) = 'SYNCED'   THEN 1 ELSE 0 END) AS synced_count,
                   SUM(CASE WHEN COALESCE(gr.status, g.status) = 'REJECTED' THEN 1 ELSE 0 END) AS rejected_count,
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
            LEFT JOIN NOTE_REPORT_REJECTION    rr ON rr.note_report_id = r.id
            LEFT JOIN NOTE_ENTREGA_DEVOLUCION  e  ON e.note_report_id  = r.id
            LEFT JOIN NOTE_PROVEEDOR           p  ON p.note_report_id  = r.id
            LEFT JOIN PROVIDER                 pv ON pv.id             = p.provider_id
            LEFT JOIN SEDE                     sd ON sd.id             = r.sede_id
            LEFT JOIN NOTE_REMITO_SEDE         rms  ON rms.note_report_id  = r.id
            LEFT JOIN SEDE_SHIPPING_INFO       rssi ON rssi.id             = rms.shipping_info_id
            LEFT JOIN NOTE_REMITO_OTHER        rmo  ON rmo.note_report_id  = r.id
            LEFT JOIN NOTE_ITEM                i  ON i.note_id         = r.id
            LEFT JOIN NOTE_ITEM_ASSET          ia ON ia.item_id        = i.id
            LEFT JOIN NOTE_ITEM_COUNTABLE      ic ON ic.item_id        = i.id
            LEFT JOIN NOTE_ITEM_STATUS_TRACKING g  ON g.item_id  = i.id AND g.tracking_type  = 'GLPI'
            LEFT JOIN NOTE_ITEM_STATUS_TRACKING gr ON gr.item_id = i.id AND gr.tracking_type = 'GLPI_RETURN'
            LEFT JOIN NOTE_ITEM_STATUS_TRACKING ir ON ir.item_id = i.id AND ir.tracking_type = 'RETURN'
            LEFT JOIN (
                SELECT item_id,
                       SUM(CASE WHEN status = 'RETURNED' THEN quantity ELSE 0 END) AS returned_qty,
                       SUM(CASE WHEN status = 'LOST'     THEN quantity ELSE 0 END) AS lost_qty
                FROM NOTE_ITEM_RETURN_ALLOCATION
                GROUP BY item_id
            ) alloc ON alloc.item_id = i.id
            """;

    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final ConfigRepository config;
    private final AuditRepository audit;

    public NotesRepository(JdbcTemplate jdbc, CatalogRepository catalog, ConfigRepository config, AuditRepository audit) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.config = config;
        this.audit = audit;
    }

    // ── Create ───────────────────────────────────────────────────────────────

    @Transactional
    public int createNote(CreateNoteRequest req, String technicianName, String technicianDni, int sedeId) {
        int reportId = insertReport(req, technicianName, technicianDni, sedeId);
        insertProfileDetail(reportId, req);
        boolean needsReturnTracking = isPrestamo(req.profileType())
                || isProviderReturnable(req.profileType(), req.motivo());
        insertItems(reportId, req.items(), req.profileType(), needsReturnTracking);
        return reportId;
    }

    private int insertReport(CreateNoteRequest req, String technicianName, String technicianDni, int sedeId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO NOTE_REPORT
                        (created_at, profile_type, technician_name, technician_dni, observations, sede_id, approval_status)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.valueOf(now));
            ps.setString(2, req.profileType());
            ps.setString(3, technicianName);
            ps.setString(4, technicianDni);
            ps.setString(5, req.observations());
            ps.setInt(6, sedeId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    private void insertProfileDetail(int reportId, CreateNoteRequest req) {
        if (isRemito(req.profileType())) {
            if (req.shippingInfoId() != null) {
                jdbc.update("INSERT INTO NOTE_REMITO_SEDE (note_report_id, shipping_info_id) VALUES (?, ?)",
                        reportId, req.shippingInfoId());
            } else {
                if (req.destinationLabel() == null || req.destinationLabel().isBlank()) {
                    throw ApiException.badRequest("REMITO_DESTINATION_REQUIRED",
                            "Un remito necesita una Sede de destino o un destino personalizado.");
                }
                jdbc.update("""
                        INSERT INTO NOTE_REMITO_OTHER (note_report_id, destination_label, address, recipients)
                        VALUES (?, ?, ?, ?)
                        """, reportId, req.destinationLabel(), req.destinationAddress(), req.destinationRecipients());
            }
            return;
        }
        if (req.isProviderNote()) {
            jdbc.update("""
                    INSERT INTO NOTE_PROVEEDOR (note_report_id, provider_id, cuit, motivo, responsible_name, responsible_dni)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, reportId, req.providerId(), req.cuit(), req.motivo(), req.responsibleName(), req.responsibleDni());
            return;
        }
        jdbc.update("""
                INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, user_dni, user_email, motivo)
                VALUES (?, ?, ?, ?, ?)
                """, reportId, req.userName(), req.userDni(), req.userEmail(), req.motivo());

        if (req.failureCause() != null && !req.failureCause().isBlank()) {
            jdbc.update("""
                    INSERT INTO NOTE_DEVOLUCION_FALLA (note_report_id, failure_cause, failure_details)
                    VALUES (?, ?, ?)
                    """, reportId, req.failureCause(), req.failureDetails());
        }
        if (req.areaEvento() != null && !req.areaEvento().isBlank()) {
            jdbc.update("""
                    INSERT INTO NOTE_PRESTAMO_AREA_EVENTO (note_report_id, area_evento)
                    VALUES (?, ?)
                    """, reportId, req.areaEvento());
        }
    }

    // Base NOTE_ITEM row can't be batched (each subtype row needs the base row's generated id
    // first) — matches the desktop app's own non-batched item loop. Subtype rows ARE batched.
    private void insertItems(int reportId, List<NoteItemRequest> items, String profileType, boolean needsReturnTracking) {
        List<Object[]> assetRows = new ArrayList<>();
        List<Object[]> countableRows = new ArrayList<>();
        List<Object[]> glpiRows = new ArrayList<>();
        List<Object[]> returnRows = new ArrayList<>();
        List<Object[]> stockExceptionRows = new ArrayList<>();

        boolean prestamo = isPrestamo(profileType);

        for (NoteItemRequest item : items) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO NOTE_ITEM (note_id, type_id, brand_id, model_id, observations, modifies_stock)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, reportId);
                ps.setInt(2, item.typeId());
                ps.setInt(3, item.brandId());
                ps.setInt(4, item.modelId());
                ps.setString(5, item.observations());
                ps.setInt(6, item.effectiveModifiesStock() ? 1 : 0);
                return ps;
            }, keyHolder);
            int itemId = keyHolder.getKey().intValue();

            if (item.isAsset()) {
                assetRows.add(new Object[] { itemId, item.serialNumber(), item.af() });
            } else {
                int quantity = item.quantity() == null ? 1 : item.quantity();
                countableRows.add(new Object[] { itemId, quantity });
            }

            // GLPI-sync eligibility: every non-Préstamo asset. Never countables, never a
            // Préstamo asset — GLPI sync in this app is one-way/no-revert (see class-level
            // Javadoc), so a loaned asset must never look "synced" while it's still out.
            if (item.isAsset() && !prestamo) {
                glpiRows.add(new Object[] { itemId, "PENDING", null, null });
            }
            if (needsReturnTracking) {
                returnRows.add(new Object[] { itemId, "PENDING", null, null });
            }
            if (!item.effectiveModifiesStock() && item.modifiesStockReason() != null
                    && !item.modifiesStockReason().isBlank()) {
                stockExceptionRows.add(new Object[] { itemId, item.modifiesStockReason() });
            }
        }

        jdbc.batchUpdate("INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f) VALUES (?, ?, ?)", assetRows);
        jdbc.batchUpdate("INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity) VALUES (?, ?)", countableRows);
        jdbc.batchUpdate("""
                INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                VALUES (?, 'GLPI', ?, ?, ?)
                """, glpiRows);
        jdbc.batchUpdate("""
                INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                VALUES (?, 'RETURN', ?, ?, ?)
                """, returnRows);
        jdbc.batchUpdate("INSERT INTO NOTE_ITEM_STOCK_EXCEPTION (item_id, reason) VALUES (?, ?)", stockExceptionRows);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public List<NoteSummaryResponse> getFiltered(NotesFilter filter) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(LIST_BASE_SQL).append(" WHERE 1=1");

        if (filter.dateFrom() != null) {
            sql.append(" AND r.created_at >= ?");
            params.add(Timestamp.valueOf(filter.dateFrom().atStartOfDay()));
        }
        if (filter.dateTo() != null) {
            sql.append(" AND r.created_at < ?");
            params.add(Timestamp.valueOf(filter.dateTo().plusDays(1).atStartOfDay()));
        }
        appendIn(sql, params, "r.profile_type", filter.profileTypes());
        appendIn(sql, params, "r.approval_status", filter.approvalStatuses());
        if (filter.sedeIds() != null && !filter.sedeIds().isEmpty()) {
            sql.append(" AND r.sede_id IN (");
            for (int i = 0; i < filter.sedeIds().size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("?");
                params.add(filter.sedeIds().get(i));
            }
            sql.append(")");
        }
        if (filter.recipientSearch() != null && !filter.recipientSearch().isBlank()) {
            sql.append(" AND COALESCE(e.user_name, pv.name, '') LIKE ?");
            params.add("%" + filter.recipientSearch().trim() + "%");
        }
        if (filter.authorSearch() != null && !filter.authorSearch().isBlank()) {
            sql.append(" AND COALESCE(r.technician_name, '') LIKE ?");
            params.add("%" + filter.authorSearch().trim() + "%");
        }
        if (hasValues(filter.itemTypes()) || hasValues(filter.itemBrands()) || hasValues(filter.itemModels())) {
            sql.append(" AND r.id IN (SELECT DISTINCT ni.note_id FROM NOTE_ITEM ni WHERE 1=1");
            appendInViaCatalog(sql, params, "ni.type_id", "TYPE", filter.itemTypes());
            appendInViaCatalog(sql, params, "ni.brand_id", "BRAND", filter.itemBrands());
            appendInViaCatalog(sql, params, "ni.model_id", "MODEL", filter.itemModels());
            sql.append(")");
        }

        sql.append(" GROUP BY r.id, r.created_at, r.profile_type, r.sede_id, r.approval_status, ")
           .append("rr.rejection_reason, r.technician_name, r.technician_dni, e.user_name, pv.name, ")
           .append("e.motivo, p.motivo, sd.name, ")
           .append("rssi.destination_label, rssi.address, rssi.recipients, ")
           .append("rmo.destination_label, rmo.address, rmo.recipients")
           .append(" ORDER BY r.created_at DESC");

        List<NoteSummaryResponse> rows = jdbc.query(sql.toString(), (rs, rowNum) -> mapSummary(rs), params.toArray());

        // sync/return status is a per-note aggregate over the counts already on each summary row,
        // not a SQL predicate — filtered here in Java, same as the desktop app's
        // filterByGlpiStatus(). Multi-select is any-of.
        rows = applyAggregateStatusFilter(rows, filter.syncStatuses(), NotesRepository::matchesSyncStatus);
        rows = applyAggregateStatusFilter(rows, filter.returnStatuses(), NotesRepository::matchesReturnStatus);
        return rows;
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

    // Matches by catalog name regardless of deprecated status, so filtering by a name a
    // type/brand/model was later renamed away from still finds the notes that used it then.
    // Ported from the desktop app's appendInViaCatalog().
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

    private static boolean hasValues(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private static List<NoteSummaryResponse> applyAggregateStatusFilter(List<NoteSummaryResponse> rows,
            List<String> statuses, BiPredicate<NoteSummaryResponse, String> matcher) {
        if (!hasValues(statuses)) return rows;
        return rows.stream().filter(r -> statuses.stream().anyMatch(s -> matcher.test(r, s))).toList();
    }

    // "N_A" is deliberately NOT "no asset items" — a Préstamo note's assets are all N_A, so the
    // count of GLPI-tracked items (pending+synced+rejected) is the right zero-check.
    private static boolean matchesSyncStatus(NoteSummaryResponse r, String status) {
        int tracked = r.pendingItemCount() + r.syncedItemCount() + r.rejectedItemCount();
        return switch (status) {
            case "N_A" -> tracked == 0;
            case "PENDING" -> r.pendingItemCount() > 0;
            case "SYNCED" -> r.syncedItemCount() > 0;
            case "REJECTED" -> r.rejectedItemCount() > 0;
            default -> true;
        };
    }

    private static boolean matchesReturnStatus(NoteSummaryResponse r, String status) {
        int tracked = r.returnPendingItemCount() + r.returnedItemCount() + r.lostItemCount();
        return switch (status) {
            case "N_A" -> tracked == 0;
            case "PENDING" -> r.returnPendingItemCount() > 0;
            case "RETURNED" -> r.returnedItemCount() > 0;
            case "LOST" -> r.lostItemCount() > 0;
            default -> true;
        };
    }

    private NoteSummaryResponse mapSummary(ResultSet rs) throws SQLException {
        int sedeIdRaw = rs.getInt("sede_id");
        Integer sedeId = rs.wasNull() ? null : sedeIdRaw;
        return new NoteSummaryResponse(
                rs.getInt("id"),
                rs.getTimestamp("created_at").toLocalDateTime().toString(),
                rs.getString("profile_type"),
                rs.getString("approval_status"),
                rs.getString("rejection_reason"),
                rs.getString("author_name"),
                rs.getString("author_dni"),
                rs.getString("recipient"),
                rs.getString("motivo"),
                rs.getString("sede"),
                sedeId,
                rs.getInt("asset_count"),
                rs.getInt("countable_count"),
                rs.getInt("pending_count"),
                rs.getInt("synced_count"),
                rs.getInt("rejected_count"),
                rs.getInt("return_pending_count"),
                rs.getInt("returned_count"),
                rs.getInt("lost_count"),
                rs.getString("destination_label"),
                rs.getString("destination_address"),
                rs.getString("destination_recipients"));
    }

    public NoteDetailResponse getById(int id) {
        String sql = """
                SELECT r.id, r.created_at, r.profile_type, r.sede_id,
                       r.approval_status, rr.rejection_reason,
                       r.technician_name AS author_name,
                       r.technician_dni AS author_dni,
                       COALESCE(r.observations, '') AS observations,
                       COALESCE(sd.name, '') AS sede,
                       COALESCE(e.user_name, '')  AS user_name,
                       COALESCE(e.user_dni, '')   AS user_dni,
                       COALESCE(e.user_email, '') AS user_email,
                       COALESCE(e.motivo, p.motivo, '') AS motivo,
                       COALESCE(fd.failure_cause, '')   AS failure_cause,
                       COALESCE(fd.failure_details, '') AS failure_details,
                       COALESCE(ae.area_evento, '')     AS area_evento,
                       pv.name AS provider_name,
                       p.provider_id AS provider_id,
                       COALESCE(p.cuit, '') AS cuit,
                       COALESCE(p.responsible_name, '') AS responsible_name,
                       COALESCE(p.responsible_dni, '')  AS responsible_dni,
                       r.stock_applied AS stock_applied,
                       rssi.sede_id AS destination_sede_id,
                       COALESCE(rssi.destination_label, rmo.destination_label, '') AS destination_label,
                       COALESCE(rssi.address, rmo.address, '')       AS destination_address,
                       COALESCE(rssi.recipients, rmo.recipients, '') AS destination_recipients
                FROM NOTE_REPORT r
                LEFT JOIN NOTE_REPORT_REJECTION     rr ON rr.note_report_id = r.id
                LEFT JOIN NOTE_ENTREGA_DEVOLUCION   e  ON e.note_report_id  = r.id
                LEFT JOIN NOTE_DEVOLUCION_FALLA     fd ON fd.note_report_id = r.id
                LEFT JOIN NOTE_PRESTAMO_AREA_EVENTO ae ON ae.note_report_id = r.id
                LEFT JOIN NOTE_PROVEEDOR            p  ON p.note_report_id  = r.id
                LEFT JOIN PROVIDER                  pv ON pv.id             = p.provider_id
                LEFT JOIN SEDE                      sd ON sd.id             = r.sede_id
                LEFT JOIN NOTE_REMITO_SEDE          rms  ON rms.note_report_id  = r.id
                LEFT JOIN SEDE_SHIPPING_INFO        rssi ON rssi.id             = rms.shipping_info_id
                LEFT JOIN NOTE_REMITO_OTHER         rmo  ON rmo.note_report_id  = r.id
                WHERE r.id = ?
                """;
        List<NoteDetailResponse> rows = jdbc.query(sql, (rs, rowNum) -> {
            Integer providerId = (Integer) rs.getObject("provider_id");
            String providerName = rs.getString("provider_name");
            return new NoteDetailResponse(
                    rs.getInt("id"),
                    rs.getTimestamp("created_at").toLocalDateTime().toString(),
                    rs.getString("profile_type"),
                    rs.getString("approval_status"),
                    rs.getString("rejection_reason"),
                    rs.getString("author_name"),
                    rs.getString("author_dni"),
                    rs.getString("observations"),
                    rs.getString("sede"),
                    rs.getInt("sede_id"),
                    rs.getString("user_name"),
                    rs.getString("user_dni"),
                    rs.getString("user_email"),
                    rs.getString("motivo"),
                    rs.getString("failure_cause"),
                    rs.getString("failure_details"),
                    rs.getString("area_evento"),
                    providerName,
                    providerId,
                    rs.getString("cuit"),
                    rs.getString("responsible_name"),
                    rs.getString("responsible_dni"),
                    rs.getInt("stock_applied") != 0,
                    (Integer) rs.getObject("destination_sede_id"),
                    rs.getString("destination_label"),
                    rs.getString("destination_address"),
                    rs.getString("destination_recipients"),
                    loadItems(rs.getInt("id")));
        }, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("NOTE_NOT_FOUND", "La nota solicitada no existe.");
        }
        return rows.get(0);
    }

    public Integer getSedeIdForNote(int noteId) {
        List<Integer> ids = jdbc.query("SELECT sede_id FROM NOTE_REPORT WHERE id = ?",
                (rs, rowNum) -> {
                    int v = rs.getInt("sede_id");
                    return rs.wasNull() ? null : v;
                }, noteId);
        if (ids.isEmpty()) {
            throw ApiException.notFound("NOTE_NOT_FOUND", "La nota solicitada no existe.");
        }
        return ids.get(0);
    }

    /**
     * Resolves an item's parent note id — the desktop REST client only ever holds an
     * {@code itemId} at an item-status-change call site (its {@code IHistoryService} interface
     * predates the REST cutover and was deliberately left unchanged rather than threading
     * {@code noteId} through every controller call site), but every sync/return endpoint's URL
     * needs {@code noteId} in the path.
     */
    public int getNoteIdForItem(int itemId) {
        List<Integer> ids = jdbc.query("SELECT note_id FROM NOTE_ITEM WHERE id = ?",
                (rs, rowNum) -> rs.getInt("note_id"), itemId);
        if (ids.isEmpty()) {
            throw ApiException.notFound("ITEM_NOT_FOUND", "El ítem solicitado no existe.");
        }
        return ids.get(0);
    }

    public boolean isAssetItem(int itemId) {
        List<Boolean> rows = jdbc.query(
                "SELECT CASE WHEN a.item_id IS NOT NULL THEN 1 ELSE 0 END AS is_asset " +
                "FROM NOTE_ITEM i LEFT JOIN NOTE_ITEM_ASSET a ON a.item_id = i.id WHERE i.id = ?",
                (rs, rowNum) -> rs.getInt("is_asset") == 1, itemId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("ITEM_NOT_FOUND", "El ítem solicitado no existe.");
        }
        return rows.get(0);
    }

    private List<NoteItemResponse> loadItems(int reportId) {
        String sql = """
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
                """;
        return jdbc.query(sql, (rs, rowNum) -> new NoteItemResponse(
                rs.getInt("id"), rs.getInt("type_id"), rs.getInt("brand_id"), rs.getInt("model_id"),
                rs.getString("type_name"), rs.getString("brand_name"), rs.getString("model_name"),
                rs.getString("serial_number"), rs.getString("a_f"), rs.getInt("quantity"),
                rs.getString("observations"), rs.getInt("modifies_stock") == 1, rs.getString("modifies_stock_reason"),
                rs.getInt("is_asset") == 1,
                rs.getString("glpi_status"), rs.getString("glpi_rejection_reason"), normalizeTimestampString(rs.getString("glpi_status_updated_at")),
                rs.getString("glpi_return_status"), rs.getString("glpi_return_rejection_reason"), normalizeTimestampString(rs.getString("glpi_return_status_updated_at")),
                rs.getString("return_status"), rs.getString("return_rejection_reason"), normalizeTimestampString(rs.getString("return_status_updated_at")),
                rs.getInt("returned_quantity"), rs.getInt("lost_quantity")),
                reportId);
    }

    // status_updated_at/updated_at columns are written as plain ISO-8601 strings (see
    // updateItemGlpiStatus()/updateItemReturnStatus()/allocateCountableReturn() below) — on SQL
    // Server that lands in a DATETIME2 column via implicit conversion, whose own getString()
    // rendering is space-separated ("yyyy-MM-dd HH:mm:ss..."), not 'T'-separated like what was
    // written. Normalizing back to 'T'-separated on read keeps this column's values consistent
    // regardless of which engine stored them — ported from the desktop app's own
    // SqliteHistoryService.normalizeTimestampString().
    private static String normalizeTimestampString(String raw) {
        if (raw == null) return null;
        int spaceIdx = raw.indexOf(' ');
        return spaceIdx < 0 ? raw : raw.substring(0, spaceIdx) + "T" + raw.substring(spaceIdx + 1);
    }

    // ── Approval ─────────────────────────────────────────────────────────────

    @Transactional
    public void updateApprovalStatus(int reportId, String status, String reason, String username) {
        if ("APPROVED".equals(status)) {
            applyNoteStockIfNeeded(reportId, username);
        }
        jdbc.update("UPDATE NOTE_REPORT SET approval_status = ? WHERE id = ?", status, reportId);
        if (reason == null || reason.isBlank()) {
            jdbc.update("DELETE FROM NOTE_REPORT_REJECTION WHERE note_report_id = ?", reportId);
        } else {
            int updated = jdbc.update(
                    "UPDATE NOTE_REPORT_REJECTION SET rejection_reason = ? WHERE note_report_id = ?",
                    reason, reportId);
            if (updated == 0) {
                jdbc.update("INSERT INTO NOTE_REPORT_REJECTION (note_report_id, rejection_reason) VALUES (?, ?)",
                        reportId, reason);
            }
        }
    }

    private record StockItemKey(int modelId, int brandId, int typeId) {
    }

    private void applyNoteStockIfNeeded(int reportId, String username) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT profile_type, sede_id, stock_applied FROM NOTE_REPORT WHERE id = ?", reportId);
        if (((Number) row.get("stock_applied")).intValue() != 0) {
            return;
        }
        String profileType = (String) row.get("profile_type");
        int sedeId = ((Number) row.get("sede_id")).intValue();

        boolean applied;
        if (isRemito(profileType)) {
            applied = applyRemitoStock(reportId, sedeId, username);
        } else if (EGRESS_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase)) {
            applied = applyDirectionalStock(reportId, sedeId, -1, true, username);
        } else if (INGRESS_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase)) {
            applied = applyDirectionalStock(reportId, sedeId, 1, false, username);
        } else {
            applied = false;
        }

        if (applied) {
            jdbc.update("UPDATE NOTE_REPORT SET stock_applied = 1 WHERE id = ?", reportId);
        }
    }

    // Remito's dual-Sede move — the source Sede (the note's own sede_id) always decrements; the
    // destination Sede increments only when it's a real catalog Sede (a custom/manual destination
    // in NOTE_REMITO_OTHER has nothing to receive). Ported from the desktop app's applyRemitoStock().
    private boolean applyRemitoStock(int reportId, int sourceSedeId, String username) {
        List<Integer> destRows = jdbc.query("""
                SELECT ssi.sede_id FROM NOTE_REMITO_SEDE rms
                JOIN SEDE_SHIPPING_INFO ssi ON ssi.id = rms.shipping_info_id
                WHERE rms.note_report_id = ?
                """, (rs, rowNum) -> rs.getInt("sede_id"), reportId);
        Integer destinationSedeId = destRows.isEmpty() ? null : destRows.get(0);

        Map<StockItemKey, Integer> quantities = aggregateItemQuantities(reportId);
        if (quantities.isEmpty()) {
            return false;
        }
        for (var entry : quantities.entrySet()) {
            StockItemKey key = entry.getKey();
            int available = catalog.getModelStock(key.modelId(), key.brandId(), key.typeId(), sourceSedeId);
            if (available < entry.getValue()) {
                throw ApiException.conflict("STOCK_WOULD_GO_NEGATIVE",
                        "Stock insuficiente en la sede de origen para aprobar este remito.");
            }
        }
        String reason = "Aprobación de remito #" + reportId;
        for (var entry : quantities.entrySet()) {
            StockItemKey key = entry.getKey();
            int qty = entry.getValue();
            int beforeSrc = catalog.getModelStock(key.modelId(), key.brandId(), key.typeId(), sourceSedeId);
            catalog.adjustModelStock(key.modelId(), key.brandId(), key.typeId(), sourceSedeId, -qty);
            audit.recordStockChange(key.modelId(), key.brandId(), key.typeId(), sourceSedeId, username,
                    beforeSrc, beforeSrc - qty, reason);
            if (destinationSedeId != null) {
                int beforeDst = catalog.getModelStock(key.modelId(), key.brandId(), key.typeId(), destinationSedeId);
                catalog.adjustModelStock(key.modelId(), key.brandId(), key.typeId(), destinationSedeId, qty);
                audit.recordStockChange(key.modelId(), key.brandId(), key.typeId(), destinationSedeId, username,
                        beforeDst, beforeDst + qty, reason);
            }
        }
        return true;
    }

    private boolean applyDirectionalStock(int reportId, int sedeId, int direction, boolean checkAvailability, String username) {
        Map<StockItemKey, Integer> quantities = aggregateItemQuantities(reportId);
        if (quantities.isEmpty()) {
            return false;
        }
        if (checkAvailability) {
            for (var entry : quantities.entrySet()) {
                StockItemKey key = entry.getKey();
                int available = catalog.getModelStock(key.modelId(), key.brandId(), key.typeId(), sedeId);
                if (available < entry.getValue()) {
                    throw ApiException.conflict("STOCK_WOULD_GO_NEGATIVE",
                            "Stock insuficiente en la sede para aprobar esta nota.");
                }
            }
        }
        // AUDIT_STOCK.reason is NOT NULL — approval-time moves have no admin-typed reason (unlike
        // the manual Base de Datos stock dialogs, which require one), so a fixed, self-explaining
        // string stands in for it.
        String reason = "Aprobación de nota #" + reportId;
        for (var entry : quantities.entrySet()) {
            StockItemKey key = entry.getKey();
            int before = catalog.getModelStock(key.modelId(), key.brandId(), key.typeId(), sedeId);
            catalog.adjustModelStock(key.modelId(), key.brandId(), key.typeId(), sedeId, direction * entry.getValue());
            int after = before + direction * entry.getValue();
            audit.recordStockChange(key.modelId(), key.brandId(), key.typeId(), sedeId, username, before, after, reason);
        }
        return true;
    }

    private Map<StockItemKey, Integer> aggregateItemQuantities(int reportId) {
        Map<StockItemKey, Integer> quantities = new LinkedHashMap<>();
        for (NoteItemResponse item : loadItems(reportId)) {
            if (!item.modifiesStock()) continue;
            StockItemKey key = new StockItemKey(item.modelId(), item.brandId(), item.typeId());
            int qty = item.asset() ? 1 : item.quantity();
            quantities.merge(key, qty, Integer::sum);
        }
        return quantities;
    }

    // ── Distinct item filter values (History filter dropdown menus) ────────
    // "Distinct values actually used in a note", not the full catalog — same convention as the
    // desktop app's SqliteHistoryService.getDistinctItemTypes()/etc. this is ported from.

    public List<String> getDistinctItemTypes() {
        return jdbc.query("""
                SELECT DISTINCT t.name FROM NOTE_ITEM ni JOIN TYPE t ON t.id = ni.type_id ORDER BY t.name
                """, (rs, rowNum) -> rs.getString(1));
    }

    public List<String> getDistinctItemBrands(List<String> types) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT b.name FROM NOTE_ITEM ni
                JOIN BRAND b ON b.id = ni.brand_id
                JOIN TYPE t ON t.id = ni.type_id
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        appendIn(sql, params, "t.name", types);
        sql.append(" ORDER BY b.name");
        return jdbc.query(sql.toString(), (rs, rowNum) -> rs.getString(1), params.toArray());
    }

    public List<String> getDistinctItemModels(List<String> types, List<String> brands) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT m.name FROM NOTE_ITEM ni
                JOIN MODEL m ON m.id = ni.model_id
                JOIN BRAND b ON b.id = ni.brand_id
                JOIN TYPE t ON t.id = ni.type_id
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        appendIn(sql, params, "t.name", types);
        appendIn(sql, params, "b.name", brands);
        sql.append(" ORDER BY m.name");
        return jdbc.query(sql.toString(), (rs, rowNum) -> rs.getString(1), params.toArray());
    }

    // ── Most-used item pinning (ItemDialogController's Type/Brand/Model combo pinning) ──
    // Ported from the desktop app's SqliteHistoryService.getMostUsedTypeNames()/etc. — top N
    // catalog entries actually used across notes created within the last windowDays days, with
    // at least minUses uses. OFFSET/FETCH instead of LIMIT — works on both SQL Server 2012+ and
    // H2, same convention as AuditRepository's paging (see IMPLEMENTED_ENDPOINTS.md).

    public List<String> getMostUsedTypeNames(int windowDays, int minUses, int limit) {
        String sql = """
                SELECT t.name AS name FROM NOTE_ITEM ni
                JOIN NOTE_REPORT r ON r.id = ni.note_id
                JOIN TYPE t ON t.id = ni.type_id
                WHERE t.deprecated = 0 AND r.created_at >= ?
                GROUP BY t.id, t.name
                HAVING COUNT(*) >= ?
                ORDER BY COUNT(*) DESC
                OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
                """;
        return jdbc.query(sql, (rs, rowNum) -> rs.getString("name"), cutoff(windowDays), minUses, limit);
    }

    public List<String> getMostUsedBrandNames(String typeName, int windowDays, int minUses, int limit) {
        String sql = """
                SELECT b.name AS name FROM NOTE_ITEM ni
                JOIN NOTE_REPORT r ON r.id = ni.note_id
                JOIN TYPE t ON t.id = ni.type_id
                JOIN BRAND b ON b.id = ni.brand_id
                WHERE t.name = ? AND b.deprecated = 0 AND r.created_at >= ?
                GROUP BY b.id, b.name
                HAVING COUNT(*) >= ?
                ORDER BY COUNT(*) DESC
                OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
                """;
        return jdbc.query(sql, (rs, rowNum) -> rs.getString("name"), typeName, cutoff(windowDays), minUses, limit);
    }

    public List<String> getMostUsedModelNames(String typeName, String brandName, int windowDays, int minUses, int limit) {
        String sql = """
                SELECT m.name AS name FROM NOTE_ITEM ni
                JOIN NOTE_REPORT r ON r.id = ni.note_id
                JOIN TYPE t ON t.id = ni.type_id
                JOIN BRAND b ON b.id = ni.brand_id
                JOIN MODEL m ON m.id = ni.model_id
                WHERE t.name = ? AND b.name = ? AND m.deprecated = 0 AND r.created_at >= ?
                GROUP BY m.id, m.name
                HAVING COUNT(*) >= ?
                ORDER BY COUNT(*) DESC
                OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
                """;
        return jdbc.query(sql, (rs, rowNum) -> rs.getString("name"), typeName, brandName, cutoff(windowDays), minUses, limit);
    }

    private Timestamp cutoff(int windowDays) {
        return Timestamp.valueOf(LocalDateTime.now().minusDays(windowDays));
    }

    // ── Item sync (GLPI dimension) ──────────────────────────────────────────

    @Transactional
    public void updateItemGlpiStatus(int itemId, String status, String reason, String username) {
        String previousStatus = currentTrackingStatus(itemId, "GLPI");
        String updatedAt = LocalDateTime.now().toString();
        int updated = jdbc.update("""
                UPDATE NOTE_ITEM_STATUS_TRACKING
                SET status = ?, rejection_reason = ?, status_updated_at = ?
                WHERE item_id = ? AND tracking_type = 'GLPI'
                """, status, reason, updatedAt, itemId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                    VALUES (?, 'GLPI', ?, ?, ?)
                    """, itemId, status, reason, updatedAt);
        }
        audit.recordItemStatusChange(itemId, "GLPI", previousStatus == null ? "N_A" : previousStatus,
                status, reason, 1, username);
    }

    // ── Item return (RETURN dimension, whole-item) ──────────────────────────

    @Transactional
    public void updateItemReturnStatus(int itemId, String status, String reason, String username) {
        String previousStatus = currentTrackingStatus(itemId, "RETURN");
        String updatedAt = LocalDateTime.now().toString();
        int updated = jdbc.update("""
                UPDATE NOTE_ITEM_STATUS_TRACKING
                SET status = ?, rejection_reason = ?, status_updated_at = ?
                WHERE item_id = ? AND tracking_type = 'RETURN'
                """, status, reason, updatedAt, itemId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                    VALUES (?, 'RETURN', ?, ?, ?)
                    """, itemId, status, reason, updatedAt);
        }
        if ("RETURNED".equals(status) && !"RETURNED".equals(previousStatus)) {
            ItemStockInfo info = resolveItemStockInfo(itemId);
            if (info != null && info.modifiesStock()) {
                catalog.adjustModelStock(info.modelId(), info.brandId(), info.typeId(), info.sedeId(), 1);
            }
            seedGlpiReturnIfProviderAsset(itemId);
        }
        audit.recordItemStatusChange(itemId, "RETURN", previousStatus == null ? "N_A" : previousStatus,
                status, reason, 1, username);
    }

    /**
     * A returnable Provider note's asset, once received back, needs its return re-synced into
     * GLPI (the original sync-out is one-way). Seed the {@code GLPI_RETURN} row at {@code PENDING}
     * so {@code /items/{id}/sync-return} becomes available — mirrors the desktop's
     * {@code handleProviderReceived}. Only for an asset that was actually GLPI-eligible (has a
     * {@code GLPI} row) on a Provider note whose Motivo is returnable; a no-op otherwise, and
     * idempotent (never a second row).
     */
    private void seedGlpiReturnIfProviderAsset(int itemId) {
        if (!isAssetItem(itemId)
                || currentTrackingStatus(itemId, "GLPI") == null
                || currentTrackingStatus(itemId, "GLPI_RETURN") != null
                || !isProviderReturnableItem(itemId)) {
            return;
        }
        jdbc.update("""
                INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                VALUES (?, 'GLPI_RETURN', 'PENDING', NULL, ?)
                """, itemId, LocalDateTime.now().toString());
    }

    private boolean isProviderReturnableItem(int itemId) {
        List<String[]> rows = jdbc.query("""
                SELECT r.profile_type, p.motivo
                FROM NOTE_ITEM i
                JOIN NOTE_REPORT r ON r.id = i.note_id
                LEFT JOIN NOTE_PROVEEDOR p ON p.note_report_id = r.id
                WHERE i.id = ?
                """, (rs, n) -> new String[] { rs.getString("profile_type"), rs.getString("motivo") }, itemId);
        return !rows.isEmpty() && isProviderReturnable(rows.get(0)[0], rows.get(0)[1]);
    }

    // ── Item return re-sync (GLPI_RETURN dimension) ─────────────────────────

    /**
     * Drive the {@code GLPI_RETURN} flag for a Provider-return asset. Only valid once the row
     * exists (seeded by the {@code RETURN → RETURNED} transition) — a {@code sync-return} before
     * the return is validated is a {@code 409}.
     */
    @Transactional
    public void updateItemGlpiReturnStatus(int itemId, String status, String reason, String username) {
        String previousStatus = currentTrackingStatus(itemId, "GLPI_RETURN");
        if (previousStatus == null) {
            throw ApiException.conflict("RETURN_NOT_VALIDATED",
                    "El ítem no tiene una devolución validada para sincronizar con GLPI.");
        }
        int updated = jdbc.update("""
                UPDATE NOTE_ITEM_STATUS_TRACKING
                SET status = ?, rejection_reason = ?, status_updated_at = ?
                WHERE item_id = ? AND tracking_type = 'GLPI_RETURN'
                """, status, reason, LocalDateTime.now().toString(), itemId);
        if (updated == 0) {
            throw ApiException.conflict("RETURN_NOT_VALIDATED",
                    "El ítem no tiene una devolución validada para sincronizar con GLPI.");
        }
        audit.recordItemStatusChange(itemId, "GLPI_RETURN", previousStatus, status, reason, 1, username);
    }

    private String currentTrackingStatus(int itemId, String trackingType) {
        List<String> rows = jdbc.query(
                "SELECT status FROM NOTE_ITEM_STATUS_TRACKING WHERE item_id = ? AND tracking_type = ?",
                (rs, rowNum) -> rs.getString("status"), itemId, trackingType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record ItemStockInfo(int modelId, int brandId, int typeId, int sedeId, boolean modifiesStock) {
    }

    private ItemStockInfo resolveItemStockInfo(int itemId) {
        List<ItemStockInfo> rows = jdbc.query("""
                SELECT i.model_id, i.brand_id, i.type_id, r.sede_id, i.modifies_stock
                FROM NOTE_ITEM i JOIN NOTE_REPORT r ON r.id = i.note_id
                WHERE i.id = ?
                """, (rs, rowNum) -> new ItemStockInfo(
                rs.getInt("model_id"), rs.getInt("brand_id"), rs.getInt("type_id"),
                rs.getInt("sede_id"), rs.getInt("modifies_stock") == 1), itemId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Countable partial return/lost allocation ────────────────────────────

    @Transactional
    public void allocateCountableReturn(int itemId, String status, int quantity, String reason, String username) {
        if (!"RETURNED".equals(status) && !"LOST".equals(status)) {
            throw ApiException.badRequest("INVALID_ALLOCATION_STATUS",
                    "El estado de la asignación debe ser Devuelto o Perdido.");
        }
        if (quantity <= 0) {
            throw ApiException.badRequest("INVALID_QUANTITY", "La cantidad a asignar debe ser mayor a cero.");
        }
        int pending = pendingCountableQuantity(itemId);
        if (quantity > pending) {
            throw ApiException.conflict("QUANTITY_EXCEEDS_PENDING",
                    "La cantidad a asignar (" + quantity + ") supera la cantidad pendiente (" + pending + ").");
        }
        jdbc.update("""
                INSERT INTO NOTE_ITEM_RETURN_ALLOCATION (item_id, status, quantity, reason, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, itemId, status, quantity, reason, LocalDateTime.now().toString());
        if ("RETURNED".equals(status)) {
            ItemStockInfo info = resolveItemStockInfo(itemId);
            if (info != null && info.modifiesStock()) {
                catalog.adjustModelStock(info.modelId(), info.brandId(), info.typeId(), info.sedeId(), quantity);
            }
        }
        // old_status is always PENDING — the only state a pending quantity can be allocated from
        // (see the desktop app's own precedent for this exact convention, documented at length in
        // its CLAUDE.md's "A second, separate write path was missed in the first pass" entry).
        audit.recordItemStatusChange(itemId, "RETURN", "PENDING", status, reason, quantity, username);
    }

    private int pendingCountableQuantity(int itemId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT ct.quantity AS qty,
                       COALESCE((SELECT SUM(quantity) FROM NOTE_ITEM_RETURN_ALLOCATION WHERE item_id = ?), 0) AS allocated
                FROM NOTE_ITEM_COUNTABLE ct
                WHERE ct.item_id = ?
                """, itemId, itemId);
        return ((Number) row.get("qty")).intValue() - ((Number) row.get("allocated")).intValue();
    }
}
