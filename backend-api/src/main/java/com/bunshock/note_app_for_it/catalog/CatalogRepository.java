package com.bunshock.note_app_for_it.catalog;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.catalog.dto.CatalogBrand;
import com.bunshock.note_app_for_it.catalog.dto.CatalogModel;
import com.bunshock.note_app_for_it.catalog.dto.CatalogProvider;
import com.bunshock.note_app_for_it.catalog.dto.CatalogSede;
import com.bunshock.note_app_for_it.catalog.dto.CatalogType;
import com.bunshock.note_app_for_it.catalog.dto.SedeShippingInfoResponse;
import com.bunshock.note_app_for_it.catalog.dto.SnValidationResponse;
import com.bunshock.note_app_for_it.catalog.dto.SnValidationRow;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.config.ConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Ported from the desktop app's {@code SqliteEquipmentService} (1015 lines) — browse, per-Sede
 * {@code MODEL_STOCK}, and the full Type/Brand/Model CRUD (deprecate-not-delete rename cascade,
 * the global "Genérico / Otro" model) are all here now. Provider/Sede are READ-ONLY from the app
 * (no CRUD methods exist in the desktop app's own service either, confirmed by reading the real
 * file — Provider/Sede management is direct-SQL-only, same convention as {@code APP_USER}).
 * SN_VALIDATION (regex-per-model S/N rules) is ported too — see the S/N Validation section below.
 * That feature is expected to be removed once the GLPI adapter lands in v2, but stays for the
 * first release (GLPI won't be up yet).
 */
@Repository
public class CatalogRepository {

    /** Mirrors the desktop app's {@code SettingsController.SN_REGEX_MAX_LENGTH} and the DB column bound. */
    private static final int SN_REGEX_MAX_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final ConfigRepository config;
    private final AuditRepository audit;

    public CatalogRepository(JdbcTemplate jdbc, ConfigRepository config, AuditRepository audit) {
        this.jdbc = jdbc;
        this.config = config;
        this.audit = audit;
    }

    /**
     * Resolves live from {@code APP_CONFIG.generic_label} on every call, not cached — matches the
     * desktop app's own precedent: {@code genericLabel} identifies "the" generic Brand/Model row
     * purely by name comparison, the same way {@code SqliteEquipmentService} always has. A real,
     * pre-existing gotcha inherited as-is, not introduced here: if an admin renames the generic
     * Brand via the catalog rename endpoint WITHOUT also updating config's genericLabel to match,
     * these identity checks silently stop recognizing the row — the old app has this same gap.
     */
    private String genericLabel() {
        return config.getGenericLabel();
    }

    public List<CatalogType> getAllTypes() {
        return jdbc.query(
                "SELECT id, name, is_asset, requires_serial FROM TYPE WHERE deprecated = 0 ORDER BY name",
                (rs, rowNum) -> new CatalogType(rs.getInt("id"), rs.getString("name"),
                        rs.getInt("is_asset") == 1, rs.getInt("requires_serial") == 1));
    }

    public List<CatalogBrand> getBrandsForType(int typeId) {
        return jdbc.query("""
                SELECT b.id, b.name FROM BRAND b
                JOIN BRAND_TYPE_LINK btl ON btl.brand_id = b.id
                WHERE btl.type_id = ? AND b.deprecated = 0 ORDER BY b.name
                """,
                (rs, rowNum) -> new CatalogBrand(rs.getInt("id"), rs.getString("name")),
                typeId);
    }

    public List<CatalogBrand> getAllBrands() {
        return jdbc.query("SELECT id, name FROM BRAND WHERE deprecated = 0 ORDER BY name",
                (rs, rowNum) -> new CatalogBrand(rs.getInt("id"), rs.getString("name")));
    }

    // The global "Genérico / Otro" model (brand_type_id IS NULL) is unioned in regardless of
    // the requested brand+type — see the desktop app's own comment on this same query, ported
    // verbatim: it's not scoped to any particular combination, so it's offered for every one of
    // them, whether or not that combination has ever had a real BRAND_TYPE_LINK.
    public List<CatalogModel> getModelsForBrandAndType(int brandId, int typeId) {
        return jdbc.query("""
                SELECT m.id, m.brand_type_id, m.name FROM MODEL m
                JOIN BRAND_TYPE_LINK btl ON btl.id = m.brand_type_id
                WHERE btl.type_id = ? AND btl.brand_id = ? AND m.deprecated = 0
                UNION
                SELECT m.id, m.brand_type_id, m.name FROM MODEL m
                WHERE m.brand_type_id IS NULL AND m.deprecated = 0
                ORDER BY name
                """,
                (rs, rowNum) -> new CatalogModel(rs.getInt("id"),
                        (Integer) rs.getObject("brand_type_id"), rs.getString("name")),
                typeId, brandId);
    }

    public Integer findBrandTypeLinkId(int brandId, int typeId) {
        List<Integer> ids = jdbc.queryForList(
                "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?",
                Integer.class, typeId, brandId);
        return ids.stream().findFirst().orElse(null);
    }

    public int ensureBrandTypeLink(int brandId, int typeId) {
        Integer existing = findBrandTypeLinkId(brandId, typeId);
        if (existing != null) {
            return existing;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, typeId);
            ps.setInt(2, brandId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    public int getModelStock(int modelId, int brandId, int typeId, int sedeId) {
        Integer linkId = findBrandTypeLinkId(brandId, typeId);
        if (linkId == null) {
            return 0;
        }
        List<Integer> stock = jdbc.queryForList(
                "SELECT stock FROM MODEL_STOCK WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?",
                Integer.class, linkId, modelId, sedeId);
        return stock.stream().findFirst().orElse(0);
    }

    /**
     * {@code reason} is required by the API layer ({@code StockUpdateRequest}'s bean validation)
     * on every call — a small simplification of the desktop app's own rule (there, a reason is
     * only demanded when the value actually changes; a no-op save skips both the reason prompt
     * and the audit row). Here the reason field is just always present in the request, but the
     * {@code AUDIT_STOCK} row itself is still only written when the value actually changes,
     * matching the "no-op save writes no audit row" half of that rule exactly.
     */
    @Transactional
    public void setModelStock(int modelId, int brandId, int typeId, int sedeId, int stock, String reason, String username) {
        // UI-layer already blocks a negative value, but this is the real persistence
        // boundary every caller goes through, so it's validated here too — ported from
        // SqliteEquipmentService.setModelStock()'s own comment.
        if (stock < 0) {
            throw ApiException.badRequest("NEGATIVE_STOCK", "El stock no puede ser negativo.");
        }
        int before = getModelStock(modelId, brandId, typeId, sedeId);
        int linkId = ensureBrandTypeLink(brandId, typeId);
        int updated = jdbc.update(
                "UPDATE MODEL_STOCK SET stock = ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?",
                stock, linkId, modelId, sedeId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)",
                    linkId, modelId, sedeId, stock);
        }
        if (before != stock) {
            audit.recordStockChange(modelId, brandId, typeId, sedeId, username, before, stock, reason);
        }
    }

    /**
     * Delta-based, unlike {@link #setModelStock}'s absolute value — read-then-write against the
     * caller's own transaction (the Notes module wraps its callers in {@code @Transactional}, so
     * this participates in that same connection/transaction rather than opening its own) so two
     * concurrent adjustments to the same (model, Sede) can't race and clobber each other. Same
     * "never go negative" rule as {@link #setModelStock}. Ported from the desktop app's
     * {@code SqliteEquipmentService.adjustModelStock()}.
     */
    public void adjustModelStock(int modelId, int brandId, int typeId, int sedeId, int delta) {
        int linkId = ensureBrandTypeLink(brandId, typeId);
        int current = getModelStock(modelId, brandId, typeId, sedeId);
        int updatedValue = current + delta;
        if (updatedValue < 0) {
            throw ApiException.badRequest("NEGATIVE_STOCK", "El stock no puede ser negativo.");
        }
        int updated = jdbc.update(
                "UPDATE MODEL_STOCK SET stock = ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?",
                updatedValue, linkId, modelId, sedeId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)",
                    linkId, modelId, sedeId, updatedValue);
        }
    }

    // ── Stock rollups (Base de Datos Type/Brand/Model list totals) ──────────
    // One query per list refresh, not one per row. Map key is the Type / Brand / Model id; a
    // missing key means 0. sedeId null = every Sede combined (SUPERADMIN's default view);
    // non-null scopes to that one Sede. Ported verbatim from the desktop app's
    // SqliteEquipmentService.getStockTotalsBy*.

    public Map<Integer, Integer> getStockTotalsByType(Integer sedeId) {
        if (sedeId == null) {
            return queryStockMap(
                    "SELECT btl.type_id, SUM(ms.stock) FROM MODEL_STOCK ms " +
                    "JOIN BRAND_TYPE_LINK btl ON btl.id = ms.brand_type_id GROUP BY btl.type_id");
        }
        return queryStockMap(
                "SELECT btl.type_id, SUM(ms.stock) FROM MODEL_STOCK ms " +
                "JOIN BRAND_TYPE_LINK btl ON btl.id = ms.brand_type_id " +
                "WHERE ms.sede_id = ? GROUP BY btl.type_id", sedeId);
    }

    public Map<Integer, Integer> getStockTotalsByBrandForType(int typeId, Integer sedeId) {
        if (sedeId == null) {
            return queryStockMap(
                    "SELECT btl.brand_id, SUM(ms.stock) FROM MODEL_STOCK ms " +
                    "JOIN BRAND_TYPE_LINK btl ON btl.id = ms.brand_type_id WHERE btl.type_id = ? " +
                    "GROUP BY btl.brand_id", typeId);
        }
        return queryStockMap(
                "SELECT btl.brand_id, SUM(ms.stock) FROM MODEL_STOCK ms " +
                "JOIN BRAND_TYPE_LINK btl ON btl.id = ms.brand_type_id " +
                "WHERE btl.type_id = ? AND ms.sede_id = ? GROUP BY btl.brand_id", typeId, sedeId);
    }

    public Map<Integer, Integer> getStockTotalsByModelForBrandAndType(int brandId, int typeId, Integer sedeId) {
        String base = """
                SELECT ms.model_id, SUM(ms.stock) FROM MODEL_STOCK ms
                WHERE ms.model_id IN (
                    SELECT m.id FROM MODEL m JOIN BRAND_TYPE_LINK btl ON btl.id = m.brand_type_id
                    WHERE btl.type_id = ? AND btl.brand_id = ? AND m.deprecated = 0
                    UNION
                    SELECT m.id FROM MODEL m WHERE m.brand_type_id IS NULL AND m.deprecated = 0
                )
                """;
        if (sedeId == null) {
            return queryStockMap(base + " GROUP BY ms.model_id", typeId, brandId);
        }
        return queryStockMap(base + " AND ms.sede_id = ? GROUP BY ms.model_id", typeId, brandId, sedeId);
    }

    private Map<Integer, Integer> queryStockMap(String sql, Object... args) {
        Map<Integer, Integer> out = new java.util.HashMap<>();
        jdbc.query(sql, (java.sql.ResultSet rs) -> { out.put(rs.getInt(1), rs.getInt(2)); }, args);
        return out;
    }

    // ── Type CRUD ────────────────────────────────────────────────────────────

    public void addType(String name, boolean isAsset, String username) {
        String trimmed = name.trim();
        if (findActiveIdExcluding("TYPE", trimmed, -1) != null) {
            throw ApiException.conflict("DUPLICATE_NAME", "Ya existe un tipo con ese nombre.");
        }
        Integer deprecatedId = findDeprecatedId("TYPE", trimmed);
        if (deprecatedId != null) {
            jdbc.update("UPDATE TYPE SET deprecated = 0, is_asset = ?, requires_serial = 0 WHERE id = ?",
                    isAsset ? 1 : 0, deprecatedId);
        } else {
            jdbc.update("INSERT INTO TYPE (name, is_asset, deprecated) VALUES (?, ?, 0)", trimmed, isAsset ? 1 : 0);
        }
        Integer newId = findActiveIdExcluding("TYPE", trimmed, -1);
        audit.recordAdminAction(username, "ADD_TYPE", "TYPE", String.valueOf(newId), null, trimmed, null);
    }

    @Transactional
    public void renameType(int typeId, String newName, String username) {
        String trimmed = newName.trim();
        Map<String, Object> row = jdbc.queryForMap("SELECT name, is_asset, requires_serial FROM TYPE WHERE id = ?", typeId);
        String currentName = (String) row.get("name");
        boolean isAsset = ((Number) row.get("is_asset")).intValue() == 1;
        boolean requiresSerial = ((Number) row.get("requires_serial")).intValue() == 1;

        if (trimmed.equalsIgnoreCase(currentName)) {
            updateName("TYPE", typeId, trimmed);
            if (!trimmed.equals(currentName)) {
                audit.recordAdminAction(username, "RENAME_TYPE", "TYPE", String.valueOf(typeId), currentName, trimmed, null);
            }
            return;
        }
        if (findActiveIdExcluding("TYPE", trimmed, typeId) != null) {
            throw ApiException.conflict("DUPLICATE_NAME", "Ya existe un tipo con ese nombre.");
        }

        int newTypeId;
        Integer deprecatedId = findDeprecatedId("TYPE", trimmed);
        if (deprecatedId != null) {
            jdbc.update("UPDATE TYPE SET deprecated = 0, is_asset = ?, requires_serial = ? WHERE id = ?",
                    isAsset ? 1 : 0, requiresSerial ? 1 : 0, deprecatedId);
            newTypeId = deprecatedId;
        } else {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO TYPE (name, is_asset, requires_serial, deprecated) VALUES (?, ?, ?, 0)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, trimmed);
                ps.setInt(2, isAsset ? 1 : 0);
                ps.setInt(3, requiresSerial ? 1 : 0);
                return ps;
            }, keyHolder);
            newTypeId = keyHolder.getKey().intValue();
        }

        setDeprecated("TYPE", typeId, true);
        cascadeAfterTypeOrBrandRename(true, typeId, newTypeId);
        audit.recordAdminAction(username, "RENAME_TYPE", "TYPE", String.valueOf(typeId), currentName, trimmed, null);
    }

    public void setRequiresSerial(int typeId, boolean requiresSerial, String username) {
        boolean before = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT requires_serial FROM TYPE WHERE id = ?", (rs, rowNum) -> rs.getInt(1) == 1, typeId));
        jdbc.update("UPDATE TYPE SET requires_serial = ? WHERE id = ?", requiresSerial ? 1 : 0, typeId);
        if (before != requiresSerial) {
            audit.recordAdminAction(username, "SET_REQUIRES_SERIAL", "TYPE", String.valueOf(typeId),
                    String.valueOf(before), String.valueOf(requiresSerial), null);
        }
    }

    public void removeType(int typeId, String username) {
        String name = findNameById("TYPE", typeId);
        setDeprecated("TYPE", typeId, true);
        audit.recordAdminAction(username, "REMOVE_TYPE", "TYPE", String.valueOf(typeId), name, null, null);
    }

    // ── Brand CRUD ───────────────────────────────────────────────────────────

    public void addBrand(String name, String username) {
        String trimmed = name.trim();
        int brandId = findOrInsertBrand(trimmed);
        audit.recordAdminAction(username, "ADD_BRAND", "BRAND", String.valueOf(brandId), null, trimmed, null);
    }

    public void addBrandForType(String brandName, int typeId, String username) {
        String trimmed = brandName.trim();
        int brandId = findOrInsertBrand(trimmed);
        // The global generic brand never needs (or should get) a BRAND_TYPE_LINK — it's already
        // offered for every type via client-side synthesis. Ported verbatim from the desktop
        // app's own comment on this exact check.
        if (genericLabel().equalsIgnoreCase(trimmed)) {
            return;
        }
        ensureBrandTypeLink(brandId, typeId);
        audit.recordAdminAction(username, "ADD_BRAND_FOR_TYPE", "BRAND", String.valueOf(brandId), null, trimmed, null);
    }

    private int findOrInsertBrand(String name) {
        Integer active = findActiveIdExcluding("BRAND", name, -1);
        if (active != null) {
            return active;
        }
        Integer deprecatedId = findDeprecatedId("BRAND", name);
        if (deprecatedId != null) {
            setDeprecated("BRAND", deprecatedId, false);
            return deprecatedId;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO BRAND (name, deprecated) VALUES (?, 0)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    @Transactional
    public void renameBrand(int brandId, String newName, String username) {
        String trimmed = newName.trim();
        String currentName = findNameById("BRAND", brandId);
        if (currentName == null) {
            throw ApiException.notFound("BRAND_NOT_FOUND", "Marca no encontrada.");
        }
        if (trimmed.equalsIgnoreCase(currentName)) {
            updateName("BRAND", brandId, trimmed);
            if (!trimmed.equals(currentName)) {
                audit.recordAdminAction(username, "RENAME_BRAND", "BRAND", String.valueOf(brandId), currentName, trimmed, null);
            }
            return;
        }
        if (findActiveIdExcluding("BRAND", trimmed, brandId) != null) {
            throw ApiException.conflict("DUPLICATE_NAME", "Ya existe una marca con ese nombre.");
        }

        int newBrandId;
        Integer deprecatedId = findDeprecatedId("BRAND", trimmed);
        if (deprecatedId != null) {
            setDeprecated("BRAND", deprecatedId, false);
            newBrandId = deprecatedId;
        } else {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO BRAND (name, deprecated) VALUES (?, 0)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, trimmed);
                return ps;
            }, keyHolder);
            newBrandId = keyHolder.getKey().intValue();
        }

        setDeprecated("BRAND", brandId, true);
        cascadeAfterTypeOrBrandRename(false, brandId, newBrandId);
        audit.recordAdminAction(username, "RENAME_BRAND", "BRAND", String.valueOf(brandId), currentName, trimmed, null);
    }

    public void removeBrand(int brandId, String username) {
        String name = findNameById("BRAND", brandId);
        String label = genericLabel();
        if (name != null && label.equalsIgnoreCase(name)) {
            throw ApiException.badRequest("CANNOT_REMOVE_GENERIC", "La marca '" + label + "' no puede eliminarse.");
        }
        setDeprecated("BRAND", brandId, true);
        audit.recordAdminAction(username, "REMOVE_BRAND", "BRAND", String.valueOf(brandId), name, null, null);
    }

    /** Resolves the actual, live "Genérico / Otro" Brand/Model catalog ids — see {@code ConfigController}. */
    public Integer findGenericBrandId() {
        return findActiveIdExcluding("BRAND", genericLabel(), -1);
    }

    public Integer findGlobalGenericModelId() {
        List<Integer> ids = jdbc.query(
                "SELECT id FROM MODEL WHERE brand_type_id IS NULL AND deprecated = 0",
                (rs, rowNum) -> rs.getInt(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ── Model CRUD ───────────────────────────────────────────────────────────

    public void addModel(String name, int brandId, int typeId, String username) {
        int brandTypeId = ensureBrandTypeLink(brandId, typeId);
        String trimmed = name.trim();
        if (activeModelIdExcluding(brandTypeId, trimmed, -1) != null) {
            throw ApiException.conflict("DUPLICATE_NAME", "Ya existe un modelo con ese nombre para esta marca y tipo.");
        }
        Integer deprecatedId = findDeprecatedModelId(brandTypeId, trimmed);
        int modelId;
        if (deprecatedId != null) {
            setDeprecated("MODEL", deprecatedId, false);
            modelId = deprecatedId;
        } else {
            modelId = insertModel(brandTypeId, trimmed, false);
        }
        audit.recordAdminAction(username, "ADD_MODEL", "MODEL", String.valueOf(modelId), null, trimmed, null);
    }

    @Transactional
    public void renameModel(int modelId, String newName, String username) {
        String trimmed = newName.trim();
        Map<String, Object> row = jdbc.queryForMap("SELECT brand_type_id, name FROM MODEL WHERE id = ?", modelId);
        Integer brandTypeId = (Integer) row.get("brand_type_id");
        String currentName = (String) row.get("name");

        if (trimmed.equalsIgnoreCase(currentName)) {
            updateName("MODEL", modelId, trimmed);
            if (!trimmed.equals(currentName)) {
                audit.recordAdminAction(username, "RENAME_MODEL", "MODEL", String.valueOf(modelId), currentName, trimmed, null);
            }
            return;
        }
        if (activeModelIdExcluding(brandTypeId, trimmed, modelId) != null) {
            throw ApiException.conflict("DUPLICATE_NAME", brandTypeId == null
                    ? "Ya existe un modelo genérico con ese nombre."
                    : "Ya existe un modelo con ese nombre para esta marca y tipo.");
        }

        // Deprecate the old row BEFORE activating its replacement — for the global generic model
        // (brandTypeId == null), only one active row is allowed at a time, so activating a second
        // one first (even briefly) would violate that. Ported verbatim from the desktop app.
        setDeprecated("MODEL", modelId, true);

        int newModelId;
        Integer deprecatedId = findDeprecatedModelId(brandTypeId, trimmed);
        if (deprecatedId != null) {
            setDeprecated("MODEL", deprecatedId, false);
            newModelId = deprecatedId;
        } else {
            newModelId = insertModel(brandTypeId, trimmed, false);
        }
        carryForwardModelStock(modelId, newModelId);
        audit.recordAdminAction(username, "RENAME_MODEL", "MODEL", String.valueOf(modelId), currentName, trimmed, null);
    }

    // The global generic model (brand_type_id IS NULL) can only ever be renamed, never removed —
    // there's nothing to scope a replacement to, so removing it would leave every "no specific
    // model" selection with nothing to point at.
    public void removeModel(int modelId, String username) {
        List<Object> brandTypeId = jdbc.query("SELECT brand_type_id FROM MODEL WHERE id = ?",
                (rs, rowNum) -> rs.getObject("brand_type_id"), modelId);
        if (!brandTypeId.isEmpty() && brandTypeId.get(0) == null) {
            throw ApiException.badRequest("CANNOT_REMOVE_GLOBAL_GENERIC", "El modelo genérico global no puede eliminarse.");
        }
        String name = findNameById("MODEL", modelId);
        setDeprecated("MODEL", modelId, true);
        audit.recordAdminAction(username, "REMOVE_MODEL", "MODEL", String.valueOf(modelId), name, null, null);
    }

    private int insertModel(Integer brandTypeId, String name, boolean deprecated) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            if (brandTypeId == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setInt(1, brandTypeId);
            }
            ps.setString(2, name);
            ps.setInt(3, deprecated ? 1 : 0);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    // brandTypeId == null scopes the lookup to the single global "Genérico / Otro" model instead
    // of a real BRAND_TYPE_LINK — same scoped-uniqueness rule either way.
    private Integer activeModelIdExcluding(Integer brandTypeId, String name, int excludeModelId) {
        String scope = brandTypeId == null ? "brand_type_id IS NULL" : "brand_type_id = ?";
        String sql = "SELECT id FROM MODEL WHERE " + scope + " AND LOWER(name) = LOWER(?) AND id != ? AND deprecated = 0";
        List<Object> params = new ArrayList<>();
        if (brandTypeId != null) params.add(brandTypeId);
        params.add(name);
        params.add(excludeModelId);
        List<Integer> ids = jdbc.query(sql, (rs, rowNum) -> rs.getInt(1), params.toArray());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Integer findDeprecatedModelId(Integer brandTypeId, String name) {
        String scope = brandTypeId == null ? "brand_type_id IS NULL" : "brand_type_id = ?";
        String sql = "SELECT id FROM MODEL WHERE " + scope + " AND LOWER(name) = LOWER(?) AND deprecated = 1";
        List<Object> params = new ArrayList<>();
        if (brandTypeId != null) params.add(brandTypeId);
        params.add(name);
        List<Integer> ids = jdbc.query(sql, (rs, rowNum) -> rs.getInt(1), params.toArray());
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ── Type/Brand rename cascade ────────────────────────────────────────────

    private void cascadeAfterTypeOrBrandRename(boolean isTypeRename, int oldId, int newId) {
        String linkColumn = isTypeRename ? "type_id" : "brand_id";
        List<int[]> links = jdbc.query(
                "SELECT id, type_id, brand_id FROM BRAND_TYPE_LINK WHERE " + linkColumn + " = ?",
                (rs, rowNum) -> new int[] { rs.getInt("id"), rs.getInt("type_id"), rs.getInt("brand_id") }, oldId);

        for (int[] link : links) {
            int oldLinkId = link[0];
            int newTypeId = isTypeRename ? newId : link[1];
            int newBrandId = isTypeRename ? link[2] : newId;
            int newLinkId = ensureBrandTypeLink(newBrandId, newTypeId);
            cloneActiveModelsForward(oldLinkId, newLinkId);
        }
    }

    private void cloneActiveModelsForward(int oldLinkId, int newLinkId) {
        if (oldLinkId == newLinkId) {
            return;
        }
        List<Object[]> models = jdbc.query(
                "SELECT id, name FROM MODEL WHERE brand_type_id = ? AND deprecated = 0",
                (rs, rowNum) -> new Object[] { rs.getInt("id"), rs.getString("name") }, oldLinkId);
        for (Object[] model : models) {
            int oldModelId = (Integer) model[0];
            String name = (String) model[1];
            Integer existingId = activeModelIdExcluding(newLinkId, name, -1);
            int newModelId = existingId != null ? existingId : insertModel(newLinkId, name, false);
            carryForwardModelStockAcrossLink(oldLinkId, oldModelId, newLinkId, newModelId);
        }
    }

    // Moves every MODEL_STOCK row (one per Sede) from (oldLinkId, oldModelId) to (newLinkId,
    // newModelId), merging into an existing row at the destination per Sede — used by the
    // Type/Brand rename cascade, where both the link AND the model row change together.
    private void carryForwardModelStockAcrossLink(int oldLinkId, int oldModelId, int newLinkId, int newModelId) {
        if (oldLinkId == newLinkId && oldModelId == newModelId) {
            return;
        }
        List<int[]> rows = jdbc.query(
                "SELECT sede_id, stock FROM MODEL_STOCK WHERE brand_type_id = ? AND model_id = ?",
                (rs, rowNum) -> new int[] { rs.getInt(1), rs.getInt(2) }, oldLinkId, oldModelId);
        for (int[] row : rows) {
            int sedeId = row[0];
            int stock = row[1];
            int updated = jdbc.update(
                    "UPDATE MODEL_STOCK SET stock = stock + ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?",
                    stock, newLinkId, newModelId, sedeId);
            if (updated == 0) {
                jdbc.update("INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)",
                        newLinkId, newModelId, sedeId, stock);
            }
        }
        jdbc.update("DELETE FROM MODEL_STOCK WHERE brand_type_id = ? AND model_id = ?", oldLinkId, oldModelId);
    }

    // Moves (merging, not overwriting) every MODEL_STOCK row from oldModelId to newModelId,
    // preserving each row's brand_type_id/sede_id — correct for a plain renameModel() call, where
    // the model's own scope never changes (only its row id does).
    private void carryForwardModelStock(int oldModelId, int newModelId) {
        if (oldModelId == newModelId) {
            return;
        }
        List<int[]> rows = jdbc.query(
                "SELECT brand_type_id, sede_id, stock FROM MODEL_STOCK WHERE model_id = ?",
                (rs, rowNum) -> new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3) }, oldModelId);
        for (int[] row : rows) {
            int brandTypeId = row[0];
            int sedeId = row[1];
            int stock = row[2];
            int updated = jdbc.update(
                    "UPDATE MODEL_STOCK SET stock = stock + ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?",
                    stock, brandTypeId, newModelId, sedeId);
            if (updated == 0) {
                jdbc.update("INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)",
                        brandTypeId, newModelId, sedeId, stock);
            }
        }
        jdbc.update("DELETE FROM MODEL_STOCK WHERE model_id = ?", oldModelId);
    }

    // ── Provider / Sede — read-only from the app (no CRUD methods exist in the desktop app's own
    //    SqliteEquipmentService either — direct-SQL-only, same convention as APP_USER) ─────────

    public List<CatalogProvider> getAllProviders() {
        return jdbc.query("SELECT id, name FROM PROVIDER WHERE deprecated = 0 ORDER BY name",
                (rs, rowNum) -> new CatalogProvider(rs.getInt("id"), rs.getString("name")));
    }

    // ── S/N Validation (regex-per-model) ─────────────────────────────────────
    //
    // Ported from SqliteEquipmentService.getSnValidation()/getAllSnValidationRows()/
    // upsertSnValidation(). One rule row per model at most (upsert is delete-then-insert). The
    // global generic model (brand_type_id IS NULL) is never listed — the panel query INNER JOINs
    // through BRAND_TYPE_LINK, same as the desktop app.

    /** Every active asset-type model, LEFT-joined to its (optional) rule — the S/N Validation panel's table. */
    public List<SnValidationRow> getAllSnValidationRows() {
        return jdbc.query("""
                SELECT t.name AS type_name, b.name AS brand_name,
                       m.id AS model_id, m.name AS model_name,
                       sv.regex_pattern, sv.is_active
                FROM MODEL m
                JOIN BRAND_TYPE_LINK btl ON btl.id = m.brand_type_id
                JOIN TYPE t ON t.id = btl.type_id
                JOIN BRAND b ON b.id = btl.brand_id
                LEFT JOIN SN_VALIDATION sv ON sv.model_id = m.id
                WHERE t.is_asset = 1 AND t.deprecated = 0 AND b.deprecated = 0 AND m.deprecated = 0
                ORDER BY COALESCE(sv.is_active, 0) DESC, t.name, b.name, m.name
                """,
                (rs, rowNum) -> new SnValidationRow(rs.getInt("model_id"), rs.getString("type_name"),
                        rs.getString("brand_name"), rs.getString("model_name"),
                        rs.getString("regex_pattern"), rs.getInt("is_active") == 1));
    }

    /** The active rule for one model, or {@code null} if none exists or it's inactive. */
    public SnValidationResponse getSnValidation(int modelId) {
        List<SnValidationResponse> rows = jdbc.query(
                "SELECT model_id, regex_pattern, is_active FROM SN_VALIDATION WHERE model_id = ? AND is_active = 1",
                (rs, rowNum) -> new SnValidationResponse(rs.getInt("model_id"), rs.getString("regex_pattern"), true),
                modelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // Unfiltered read (any is_active value) — only used to build the audit before-image, so a
    // deactivate/reactivate is recorded accurately, matching what the desktop panel shows.
    private SnValidationResponse rawSnValidation(int modelId) {
        List<SnValidationResponse> rows = jdbc.query(
                "SELECT model_id, regex_pattern, is_active FROM SN_VALIDATION WHERE model_id = ?",
                (rs, rowNum) -> new SnValidationResponse(rs.getInt("model_id"),
                        rs.getString("regex_pattern"), rs.getInt("is_active") == 1),
                modelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public void upsertSnValidation(int modelId, String regex, boolean active, String username) {
        if (findNameById("MODEL", modelId) == null) {
            throw ApiException.notFound("MODEL_NOT_FOUND", "Modelo no encontrado.");
        }
        String normalized = (regex == null || regex.isBlank()) ? null : regex.trim();
        if (normalized != null) {
            // Length is also enforced by SnValidationUpdateRequest's @Size, but this is the real
            // persistence boundary every caller crosses — same belt-and-braces as setModelStock().
            if (normalized.length() > SN_REGEX_MAX_LENGTH) {
                throw ApiException.badRequest("REGEX_TOO_LONG",
                        "La expresión regular no puede superar los " + SN_REGEX_MAX_LENGTH + " caracteres.");
            }
            // The desktop app's SettingsController does exactly this check before saving — a
            // pattern that only fails at Pattern.compile() time would otherwise throw on every
            // keystroke a technician makes against this model in ItemDialogController.
            try {
                Pattern.compile(normalized);
            } catch (PatternSyntaxException e) {
                throw ApiException.badRequest("INVALID_REGEX", "Expresión regular inválida: " + e.getDescription());
            }
        }

        SnValidationResponse before = rawSnValidation(modelId);
        jdbc.update("DELETE FROM SN_VALIDATION WHERE model_id = ?", modelId);
        jdbc.update("INSERT INTO SN_VALIDATION (model_id, regex_pattern, is_active) VALUES (?, ?, ?)",
                modelId, normalized, active ? 1 : 0);

        String oldValue = snAuditValue(before == null ? null : before.regexPattern(),
                before != null && before.active());
        String newValue = snAuditValue(normalized, active);
        if (!oldValue.equals(newValue)) {
            audit.recordAdminAction(username, "EDIT_SN_VALIDATION", "SN_VALIDATION",
                    String.valueOf(modelId), oldValue, newValue, null);
        }
    }

    // Same "regex=…, activa=…" shape SettingsController's own audit call uses.
    private static String snAuditValue(String regex, boolean active) {
        return "regex=" + (regex == null ? "" : regex) + ", activa=" + active;
    }

    public List<CatalogSede> getAllSedes() {
        return jdbc.query("SELECT id, name FROM SEDE WHERE deprecated = 0 ORDER BY name",
                (rs, rowNum) -> new CatalogSede(rs.getInt("id"), rs.getString("name")));
    }

    public SedeShippingInfoResponse getSedeShippingInfo(int sedeId) {
        List<SedeShippingInfoResponse> rows = jdbc.query(
                "SELECT id, destination_label, address, recipients FROM SEDE_SHIPPING_INFO WHERE sede_id = ? AND deprecated = 0",
                (rs, rowNum) -> new SedeShippingInfoResponse(rs.getInt("id"), sedeId,
                        rs.getString("destination_label"), rs.getString("address"), rs.getString("recipients")),
                sedeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // One query for the whole set — the desktop app filters the Remito destination combo to only
    // Sedes that actually have active shipping info (an unconfigured Sede can't be a
    // NOTE_REMITO_SEDE destination, since shipping_info_id is NOT NULL). Ported from
    // IEquipmentService.getSedeIdsWithShippingInfo().
    public Set<Integer> getSedeIdsWithShippingInfo() {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT DISTINCT sede_id FROM SEDE_SHIPPING_INFO WHERE deprecated = 0", Integer.class));
    }

    // ── Generic name-scoped helpers (TYPE/BRAND — uniqueness on `name` holds across a table
    //    regardless of deprecated status, so add/rename always resolves against these first) ──

    private Integer findActiveIdExcluding(String table, String name, int excludeId) {
        List<Integer> ids = jdbc.query(
                "SELECT id FROM " + table + " WHERE LOWER(name) = LOWER(?) AND id != ? AND deprecated = 0",
                (rs, rowNum) -> rs.getInt(1), name, excludeId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Integer findDeprecatedId(String table, String name) {
        List<Integer> ids = jdbc.query(
                "SELECT id FROM " + table + " WHERE LOWER(name) = LOWER(?) AND deprecated = 1",
                (rs, rowNum) -> rs.getInt(1), name);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findNameById(String table, int id) {
        List<String> names = jdbc.query("SELECT name FROM " + table + " WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), id);
        return names.isEmpty() ? null : names.get(0);
    }

    private void updateName(String table, int id, String name) {
        jdbc.update("UPDATE " + table + " SET name = ? WHERE id = ?", name, id);
    }

    private void setDeprecated(String table, int id, boolean deprecated) {
        jdbc.update("UPDATE " + table + " SET deprecated = ? WHERE id = ?", deprecated ? 1 : 0, id);
    }
}
