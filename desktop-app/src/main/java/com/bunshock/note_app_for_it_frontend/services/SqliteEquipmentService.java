package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.Sede;
import com.bunshock.note_app_for_it_frontend.models.SedeShippingInfo;
import com.bunshock.note_app_for_it_frontend.models.SnValidation;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;

public class SqliteEquipmentService implements IEquipmentService {

    private static final String DEFAULT_GENERIC_LABEL = "Genérico / Otro";

    // BRAND has no structural way to mark "this is the fallback row" the way MODEL does
    // (brand_type_id IS NULL) — no scoping FK to leave null — so it's still identified by name.
    // Reads the live config value rather than a hardcoded constant so a renamed fallback brand
    // stays protected as long as an admin keeps catalog.genericLabel in sync with the rename.
    private String genericLabel() {
        try {
            AppConfig.CatalogConfig catalog = ConfigService.getInstance().getConfig().catalog;
            if (catalog != null && catalog.genericLabel != null && !catalog.genericLabel.isBlank()) {
                return catalog.genericLabel.trim();
            }
        } catch (IllegalStateException notLoaded) {
            // ConfigService not loaded in this context (e.g. some test setups) — use the default
        }
        return DEFAULT_GENERIC_LABEL;
    }

    private final Supplier<Connection> connector;

    public SqliteEquipmentService() {
        this.connector = () -> {
            try { return DatabaseService.getInstance().getConnection(); }
            catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        };
    }

    SqliteEquipmentService(Supplier<Connection> connector) {
        this.connector = connector;
    }

    @Override
    public List<EquipmentType> getAllTypes() {
        List<EquipmentType> result = new ArrayList<>();
        try (Connection c = connector.get();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT id, name, is_asset, requires_serial FROM TYPE WHERE deprecated = 0 ORDER BY name")) {
            while (rs.next()) {
                result.add(new EquipmentType(rs.getInt("id"), rs.getString("name"),
                    rs.getInt("is_asset") == 1, rs.getInt("requires_serial") == 1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load types", e);
        }
        return result;
    }

    @Override
    public List<EquipmentBrand> getBrandsForType(int typeId) {
        List<EquipmentBrand> result = new ArrayList<>();
        String sql = """
            SELECT b.id, b.name FROM BRAND b
            JOIN BRAND_TYPE_LINK btl ON btl.brand_id = b.id
            WHERE btl.type_id = ? AND b.deprecated = 0 ORDER BY b.name
            """;
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, typeId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new EquipmentBrand(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load brands for type " + typeId, e);
        }
        return result;
    }

    @Override
    public List<EquipmentBrand> getAllBrands() {
        List<EquipmentBrand> result = new ArrayList<>();
        try (Connection c = connector.get();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT id, name FROM BRAND WHERE deprecated = 0 ORDER BY name")) {
            while (rs.next()) {
                result.add(new EquipmentBrand(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load brands", e);
        }
        return result;
    }

    // The global "Genérico / Otro" model (brand_type_id IS NULL) is unioned in regardless of the
    // requested brand+type — it's not scoped to any particular combination, so it's offered for
    // every one of them, whether or not that combination has ever had a real BRAND_TYPE_LINK.
    @Override
    public List<EquipmentModel> getModelsForBrandAndType(int brandId, int typeId) {
        List<EquipmentModel> result = new ArrayList<>();
        String sql = """
            SELECT m.id, m.brand_type_id, m.name FROM MODEL m
            JOIN BRAND_TYPE_LINK btl ON btl.id = m.brand_type_id
            WHERE btl.type_id = ? AND btl.brand_id = ? AND m.deprecated = 0
            UNION
            SELECT m.id, m.brand_type_id, m.name FROM MODEL m
            WHERE m.brand_type_id IS NULL AND m.deprecated = 0
            ORDER BY name
            """;
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, typeId);
            ps.setInt(2, brandId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new EquipmentModel(rs.getInt("id"),
                    (Integer) rs.getObject("brand_type_id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load models", e);
        }
        return result;
    }

    @Override
    public List<EquipmentProvider> getAllProviders() {
        List<EquipmentProvider> result = new ArrayList<>();
        try (Connection c = connector.get();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT id, name FROM PROVIDER WHERE deprecated = 0 ORDER BY name")) {
            while (rs.next()) {
                result.add(new EquipmentProvider(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load providers", e);
        }
        return result;
    }

    @Override
    public List<Sede> getAllSedes() {
        List<Sede> result = new ArrayList<>();
        try (Connection c = connector.get();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT id, name FROM SEDE WHERE deprecated = 0 ORDER BY name")) {
            while (rs.next()) {
                result.add(new Sede(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load sedes", e);
        }
        return result;
    }

    @Override
    public Optional<SedeShippingInfo> getSedeShippingInfo(int sedeId) {
        String sql = "SELECT id, destination_label, address, recipients FROM SEDE_SHIPPING_INFO WHERE sede_id = ? AND deprecated = 0";
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, sedeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new SedeShippingInfo(rs.getInt("id"), sedeId,
                    rs.getString("destination_label"), rs.getString("address"), rs.getString("recipients")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load sede shipping info", e);
        }
    }

    @Override
    public java.util.Set<Integer> getSedeIdsWithShippingInfo() {
        java.util.Set<Integer> result = new java.util.HashSet<>();
        try (Connection c = connector.get();
             ResultSet rs = c.createStatement().executeQuery(
                 "SELECT DISTINCT sede_id FROM SEDE_SHIPPING_INFO WHERE deprecated = 0")) {
            while (rs.next()) result.add(rs.getInt("sede_id"));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load sede ids with shipping info", e);
        }
        return result;
    }

    @Override
    public Optional<SnValidation> getSnValidation(int modelId) {
        // No LIMIT/TOP clause needed — upsertSnValidation()'s delete-then-insert already
        // guarantees at most one row per model_id, and dropping it keeps this query dialect-
        // neutral (SQL Server has no LIMIT; TOP has different placement syntax).
        String sql = "SELECT * FROM SN_VALIDATION WHERE model_id = ? AND is_active = 1";
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, modelId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new SnValidation(
                    rs.getInt("model_id"),
                    rs.getString("regex_pattern"),
                    true
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load SN validation", e);
        }
        return Optional.empty();
    }

    @Override
    public List<SnValidationRow> getAllSnValidationRows() {
        String sql = """
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
            """;
        List<SnValidationRow> rows = new ArrayList<>();
        try (Connection c = connector.get();
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new SnValidationRow(
                    rs.getInt("model_id"),
                    rs.getString("type_name"),
                    rs.getString("brand_name"),
                    rs.getString("model_name"),
                    rs.getString("regex_pattern"),
                    rs.getInt("is_active") == 1
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load SN validation rows", e);
        }
        return rows;
    }

    @Override
    public void upsertSnValidation(int modelId, String regex, boolean active) {
        try (Connection c = connector.get()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM SN_VALIDATION WHERE model_id = ?")) {
                del.setInt(1, modelId);
                del.executeUpdate();
            }
            String r = (regex == null || regex.isBlank()) ? null : regex.trim();
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO SN_VALIDATION (model_id, regex_pattern, is_active) VALUES (?, ?, ?)")) {
                ins.setInt(1, modelId);
                ins.setString(2, r);
                ins.setInt(3, active ? 1 : 0);
                ins.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert SN validation", e);
        }
    }

    @Override
    public void addBrandForType(String brandName, int typeId) {
        try (Connection c = connector.get()) {
            String trimmed = brandName.trim();
            int brandId = findOrInsertBrand(c, trimmed);
            // The global generic brand never needs (or should get) a BRAND_TYPE_LINK — it's
            // already offered for every type via client-side synthesis
            // (ItemDialogController.onTypeSelected(), DatabaseSectionController.
            // refreshBrandsForType()). Creating a real link here would reproduce the exact
            // per-type inconsistency the cleanup migration removes: that one type
            // would show it via getBrandsForType()'s real JOIN (sorted alphabetically among
            // real brands) while every other type shows it via synthesis (always appended last).
            if (genericLabel().equalsIgnoreCase(trimmed)) return;
            ensureBrandTypeLink(c, brandId, typeId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add brand for type", e);
        }
    }

    // Reuses (reactivates) a deprecated row with the same name rather than inserting a duplicate
    // — name uniqueness on BRAND holds regardless of deprecated status (see rename methods below),
    // so a straight INSERT here would violate that constraint the moment the same brand name was
    // ever deprecated in the past (e.g. renamed away and back).
    private int findOrInsertBrand(Connection c, String name) throws SQLException {
        Integer active = findActiveIdExcluding(c, "BRAND", name, -1);
        if (active != null) return active;

        Integer deprecatedId = findDeprecatedId(c, "BRAND", name);
        if (deprecatedId != null) {
            try (PreparedStatement up = c.prepareStatement("UPDATE BRAND SET deprecated = 0 WHERE id = ?")) {
                up.setInt(1, deprecatedId);
                up.executeUpdate();
            }
            return deprecatedId;
        }

        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO BRAND (name, deprecated) VALUES (?, 0)", Statement.RETURN_GENERATED_KEYS)) {
            ins.setString(1, name);
            ins.executeUpdate();
            try (ResultSet keys = ins.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    @Override
    public void renameType(int typeId, String newName) {
        try (Connection c = connector.get()) {
            String trimmed = newName.trim();

            String currentName;
            boolean isAsset;
            boolean requiresSerial;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT name, is_asset, requires_serial FROM TYPE WHERE id = ?")) {
                sel.setInt(1, typeId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Tipo no encontrado");
                    currentName = rs.getString("name");
                    isAsset = rs.getInt("is_asset") == 1;
                    requiresSerial = rs.getInt("requires_serial") == 1;
                }
            }

            if (trimmed.equalsIgnoreCase(currentName)) {
                updateName(c, "TYPE", typeId, trimmed);
                return;
            }
            if (findActiveIdExcluding(c, "TYPE", trimmed, typeId) != null) {
                throw new IllegalArgumentException("Ya existe un tipo con ese nombre");
            }

            int newTypeId;
            Integer deprecatedId = findDeprecatedId(c, "TYPE", trimmed);
            if (deprecatedId != null) {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE TYPE SET deprecated = 0, is_asset = ?, requires_serial = ? WHERE id = ?")) {
                    up.setInt(1, isAsset ? 1 : 0);
                    up.setInt(2, requiresSerial ? 1 : 0);
                    up.setInt(3, deprecatedId);
                    up.executeUpdate();
                }
                newTypeId = deprecatedId;
            } else {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO TYPE (name, is_asset, requires_serial, deprecated) VALUES (?, ?, ?, 0)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ins.setString(1, trimmed);
                    ins.setInt(2, isAsset ? 1 : 0);
                    ins.setInt(3, requiresSerial ? 1 : 0);
                    ins.executeUpdate();
                    try (ResultSet keys = ins.getGeneratedKeys()) {
                        keys.next();
                        newTypeId = keys.getInt(1);
                    }
                }
            }

            setDeprecated(c, "TYPE", typeId, true);
            cascadeAfterTypeOrBrandRename(c, true, typeId, newTypeId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rename type", e);
        }
    }

    @Override
    public void setRequiresSerial(int typeId, boolean requiresSerial) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("UPDATE TYPE SET requires_serial = ? WHERE id = ?")) {
            ps.setInt(1, requiresSerial ? 1 : 0);
            ps.setInt(2, typeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update requires_serial for type", e);
        }
    }

    @Override
    public void renameBrand(int brandId, String newName) {
        try (Connection c = connector.get()) {
            String trimmed = newName.trim();
            String currentName = findNameById(c, "BRAND", brandId);
            if (currentName == null) throw new IllegalArgumentException("Marca no encontrada");

            if (trimmed.equalsIgnoreCase(currentName)) {
                updateName(c, "BRAND", brandId, trimmed);
                return;
            }
            if (findActiveIdExcluding(c, "BRAND", trimmed, brandId) != null) {
                throw new IllegalArgumentException("Ya existe una marca con ese nombre");
            }

            int newBrandId;
            Integer deprecatedId = findDeprecatedId(c, "BRAND", trimmed);
            if (deprecatedId != null) {
                setDeprecated(c, "BRAND", deprecatedId, false);
                newBrandId = deprecatedId;
            } else {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO BRAND (name, deprecated) VALUES (?, 0)", Statement.RETURN_GENERATED_KEYS)) {
                    ins.setString(1, trimmed);
                    ins.executeUpdate();
                    try (ResultSet keys = ins.getGeneratedKeys()) {
                        keys.next();
                        newBrandId = keys.getInt(1);
                    }
                }
            }

            setDeprecated(c, "BRAND", brandId, true);
            cascadeAfterTypeOrBrandRename(c, false, brandId, newBrandId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rename brand", e);
        }
    }

    @Override
    public void renameModel(int modelId, String newName) {
        try (Connection c = connector.get()) {
            String trimmed = newName.trim();
            Integer brandTypeId;
            String currentName;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT brand_type_id, name FROM MODEL WHERE id = ?")) {
                sel.setInt(1, modelId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Modelo no encontrado");
                    brandTypeId = (Integer) rs.getObject("brand_type_id");
                    currentName = rs.getString("name");
                }
            }

            if (trimmed.equalsIgnoreCase(currentName)) {
                updateName(c, "MODEL", modelId, trimmed);
                return;
            }
            if (activeModelIdExcluding(c, brandTypeId, trimmed, modelId) != null) {
                throw new IllegalArgumentException(brandTypeId == null
                    ? "Ya existe un modelo genérico con ese nombre"
                    : "Ya existe un modelo con ese nombre para esta marca y tipo");
            }

            // Deprecate the old row BEFORE activating its replacement — for the global generic
            // model (brandTypeId == null), idx_model_single_active_generic allows only one
            // active row at a time, so activating a second one first (even briefly, within the
            // same rename) would violate it. Harmless reordering for a normal scoped model too,
            // since idx_model_brand_type_name keys on name and the two rows never share one.
            setDeprecated(c, "MODEL", modelId, true);

            int newModelId;
            Integer deprecatedId = findDeprecatedModelId(c, brandTypeId, trimmed);
            if (deprecatedId != null) {
                setDeprecated(c, "MODEL", deprecatedId, false);
                newModelId = deprecatedId;
            } else {
                newModelId = insertModel(c, brandTypeId, trimmed, false);
            }
            // A rename swaps to a different MODEL row id — MODEL_STOCK's PK includes model_id,
            // so without this the old row's stock would silently vanish from every rollup.
            carryForwardModelStock(c, modelId, newModelId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rename model", e);
        }
    }

    private int insertModel(Connection c, Integer brandTypeId, String name, boolean deprecated) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            if (brandTypeId == null) ins.setNull(1, Types.INTEGER);
            else ins.setInt(1, brandTypeId);
            ins.setString(2, name);
            ins.setInt(3, deprecated ? 1 : 0);
            ins.executeUpdate();
            try (ResultSet keys = ins.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    // brandTypeId == null scopes the lookup to the single global "Genérico / Otro" model
    // (brand_type_id IS NULL) instead of a real BRAND_TYPE_LINK — the same scoped-uniqueness
    // rule applies either way (a name may only ever live on one row at a time within its scope).
    private Integer activeModelIdExcluding(Connection c, Integer brandTypeId, String name, int excludeModelId) throws SQLException {
        String scope = brandTypeId == null ? "brand_type_id IS NULL" : "brand_type_id = ?";
        String sql = "SELECT id FROM MODEL WHERE " + scope
            + " AND LOWER(name) = LOWER(?) AND id != ? AND deprecated = 0";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            if (brandTypeId != null) ps.setInt(idx++, brandTypeId);
            ps.setString(idx++, name);
            ps.setInt(idx, excludeModelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private Integer findDeprecatedModelId(Connection c, Integer brandTypeId, String name) throws SQLException {
        String scope = brandTypeId == null ? "brand_type_id IS NULL" : "brand_type_id = ?";
        String sql = "SELECT id FROM MODEL WHERE " + scope
            + " AND LOWER(name) = LOWER(?) AND deprecated = 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            if (brandTypeId != null) ps.setInt(idx++, brandTypeId);
            ps.setString(idx, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    @Override
    public void addType(String name, boolean isAsset) {
        try (Connection c = connector.get()) {
            String trimmed = name.trim();
            if (findActiveIdExcluding(c, "TYPE", trimmed, -1) != null) {
                throw new IllegalArgumentException("Ya existe un tipo con ese nombre");
            }

            Integer deprecatedId = findDeprecatedId(c, "TYPE", trimmed);
            if (deprecatedId != null) {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE TYPE SET deprecated = 0, is_asset = ?, requires_serial = 0 WHERE id = ?")) {
                    up.setInt(1, isAsset ? 1 : 0);
                    up.setInt(2, deprecatedId);
                    up.executeUpdate();
                }
                return;
            }

            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO TYPE (name, is_asset, deprecated) VALUES (?, ?, 0)")) {
                ins.setString(1, trimmed);
                ins.setInt(2, isAsset ? 1 : 0);
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add type", e);
        }
    }

    @Override
    public void addBrand(String name) {
        try (Connection c = connector.get()) {
            findOrInsertBrand(c, name.trim());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add brand", e);
        }
    }

    @Override
    public void addModel(String name, int brandId, int typeId) {
        try (Connection c = connector.get()) {
            int brandTypeId = ensureBrandTypeLink(c, brandId, typeId);
            String trimmed = name.trim();

            if (activeModelIdExcluding(c, brandTypeId, trimmed, -1) != null) {
                throw new IllegalArgumentException("Ya existe un modelo con ese nombre para esta marca y tipo");
            }

            Integer deprecatedId = findDeprecatedModelId(c, brandTypeId, trimmed);
            if (deprecatedId != null) {
                setDeprecated(c, "MODEL", deprecatedId, false);
                return;
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (?, ?, 0)")) {
                ps.setInt(1, brandTypeId);
                ps.setString(2, trimmed);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add model", e);
        }
    }

    @Override
    public void removeType(int typeId) {
        try (Connection c = connector.get()) {
            setDeprecated(c, "TYPE", typeId, true);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove type", e);
        }
    }

    @Override
    public void removeBrand(int brandId) {
        try (Connection c = connector.get()) {
            String name = findNameById(c, "BRAND", brandId);
            if (name != null && genericLabel().equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("La marca '" + genericLabel() + "' no puede eliminarse");
            }
            setDeprecated(c, "BRAND", brandId, true);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove brand", e);
        }
    }

    // The global generic model (brand_type_id IS NULL) can only ever be renamed, never removed —
    // it can't be recreated on demand the way a real per-scope model can (there's nothing to
    // scope it to), so removing it would leave every "no specific model" selection with nothing
    // to point at.
    @Override
    public void removeModel(int modelId) {
        try (Connection c = connector.get()) {
            boolean isGlobalGeneric = false;
            try (PreparedStatement sel = c.prepareStatement("SELECT brand_type_id FROM MODEL WHERE id = ?")) {
                sel.setInt(1, modelId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) isGlobalGeneric = rs.getObject("brand_type_id") == null;
                }
            }
            if (isGlobalGeneric) {
                throw new IllegalArgumentException("El modelo genérico global no puede eliminarse");
            }
            setDeprecated(c, "MODEL", modelId, true);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove model", e);
        }
    }

    private int ensureBrandTypeLink(Connection c, int brandId, int typeId) throws SQLException {
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?")) {
            sel.setInt(1, typeId);
            sel.setInt(2, brandId);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) return rs.getInt("id");
            }
        }

        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ins.setInt(1, typeId);
            ins.setInt(2, brandId);
            ins.executeUpdate();
            try (ResultSet keys = ins.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    // A Type/Brand rename deprecates the row every BRAND_TYPE_LINK using it points at, which
    // would otherwise silently orphan that link's active catalog (its models would still exist,
    // but be unreachable from the UI since they hang off a link whose type/brand is now
    // deprecated). For every such link, an equivalent link under the new id is ensured, and every
    // still-active model under the old link is cloned forward onto it — idempotent, so re-running
    // (e.g. two renames that happen to converge on the same combination) never duplicates rows.
    private void cascadeAfterTypeOrBrandRename(Connection c, boolean isTypeRename, int oldId, int newId)
            throws SQLException {
        String linkColumn = isTypeRename ? "type_id" : "brand_id";
        List<int[]> links = new ArrayList<>();
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT id, type_id, brand_id FROM BRAND_TYPE_LINK WHERE " + linkColumn + " = ?")) {
            sel.setInt(1, oldId);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    links.add(new int[] {rs.getInt("id"), rs.getInt("type_id"), rs.getInt("brand_id")});
                }
            }
        }

        for (int[] link : links) {
            int oldLinkId = link[0];
            int newTypeId = isTypeRename ? newId : link[1];
            int newBrandId = isTypeRename ? link[2] : newId;
            int newLinkId = ensureBrandTypeLink(c, newBrandId, newTypeId);
            cloneActiveModelsForward(c, oldLinkId, newLinkId);
        }
    }

    private void cloneActiveModelsForward(Connection c, int oldLinkId, int newLinkId) throws SQLException {
        if (oldLinkId == newLinkId) return;
        List<Object[]> models = new ArrayList<>(); // [id, name]
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT id, name FROM MODEL WHERE brand_type_id = ? AND deprecated = 0")) {
            sel.setInt(1, oldLinkId);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) models.add(new Object[] {rs.getInt("id"), rs.getString("name")});
            }
        }
        for (Object[] model : models) {
            int oldModelId = (Integer) model[0];
            String name = (String) model[1];
            Integer existingId = activeModelIdExcluding(c, newLinkId, name, -1);
            int newModelId = existingId != null ? existingId : insertModel(c, newLinkId, name, false);
            // Unlike a plain renameModel() (where the model's own brand_type_id never changes),
            // the whole point of this cascade is that the scope itself moved from oldLinkId to
            // newLinkId — carryForwardModelStock() alone would incorrectly leave the stock keyed
            // to the now-stale oldLinkId, so the link id must be remapped here too, not just the
            // model id.
            carryForwardModelStockAcrossLink(c, oldLinkId, oldModelId, newLinkId, newModelId);
        }
    }

    // Moves every MODEL_STOCK row (one per Sede) from (oldLinkId, oldModelId) to
    // (newLinkId, newModelId), merging into an existing row at the destination per Sede rather
    // than overwriting it — used by the Type/Brand rename cascade above, where both the link and
    // the model row change together.
    private void carryForwardModelStockAcrossLink(Connection c, int oldLinkId, int oldModelId,
            int newLinkId, int newModelId) throws SQLException {
        if (oldLinkId == newLinkId && oldModelId == newModelId) return;
        List<int[]> rows = new ArrayList<>(); // [sedeId, stock]
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT sede_id, stock FROM MODEL_STOCK WHERE brand_type_id = ? AND model_id = ?")) {
            sel.setInt(1, oldLinkId);
            sel.setInt(2, oldModelId);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) rows.add(new int[] {rs.getInt(1), rs.getInt(2)});
            }
        }
        for (int[] row : rows) {
            int sedeId = row[0];
            int stock = row[1];
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE MODEL_STOCK SET stock = stock + ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?")) {
                up.setInt(1, stock);
                up.setInt(2, newLinkId);
                up.setInt(3, newModelId);
                up.setInt(4, sedeId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)")) {
                        ins.setInt(1, newLinkId);
                        ins.setInt(2, newModelId);
                        ins.setInt(3, sedeId);
                        ins.setInt(4, stock);
                        ins.executeUpdate();
                    }
                }
            }
        }
        try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM MODEL_STOCK WHERE brand_type_id = ? AND model_id = ?")) {
            del.setInt(1, oldLinkId);
            del.setInt(2, oldModelId);
            del.executeUpdate();
        }
    }

    // Moves (merging, not overwriting) every MODEL_STOCK row — one per Sede — from oldModelId to
    // newModelId, preserving each row's existing brand_type_id/sede_id — correct for a plain
    // renameModel() call, where the model's own scope never changes (only its row id does). NOT
    // used by the Type/Brand cascade above, since that changes the scope (link) too — see
    // carryForwardModelStockAcrossLink() for that case.
    private void carryForwardModelStock(Connection c, int oldModelId, int newModelId) throws SQLException {
        if (oldModelId == newModelId) return;
        List<int[]> rows = new ArrayList<>(); // [brand_type_id, sede_id, stock]
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT brand_type_id, sede_id, stock FROM MODEL_STOCK WHERE model_id = ?")) {
            sel.setInt(1, oldModelId);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) rows.add(new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)});
            }
        }
        for (int[] row : rows) {
            int brandTypeId = row[0];
            int sedeId = row[1];
            int stock = row[2];
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE MODEL_STOCK SET stock = stock + ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?")) {
                up.setInt(1, stock);
                up.setInt(2, brandTypeId);
                up.setInt(3, newModelId);
                up.setInt(4, sedeId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)")) {
                        ins.setInt(1, brandTypeId);
                        ins.setInt(2, newModelId);
                        ins.setInt(3, sedeId);
                        ins.setInt(4, stock);
                        ins.executeUpdate();
                    }
                }
            }
        }
        try (PreparedStatement del = c.prepareStatement("DELETE FROM MODEL_STOCK WHERE model_id = ?")) {
            del.setInt(1, oldModelId);
            del.executeUpdate();
        }
    }

    // ── Stock (Base de Datos: Type/Brand/Model rollups) ─────────────────

    @Override
    public int getModelStock(int modelId, int brandId, int typeId, int sedeId) {
        try (Connection c = connector.get()) {
            Integer linkId = findBrandTypeLinkId(c, brandId, typeId);
            if (linkId == null) return 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT stock FROM MODEL_STOCK WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?")) {
                ps.setInt(1, linkId);
                ps.setInt(2, modelId);
                ps.setInt(3, sedeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load model stock", e);
        }
    }

    @Override
    public void setModelStock(int modelId, int brandId, int typeId, int sedeId, int stock) {
        // The UI's tfStock TextFormatter already blocks a minus sign, but this is the actual
        // persistence boundary every caller goes through, so it's validated here too.
        if (stock < 0) {
            throw new IllegalArgumentException("El stock no puede ser negativo");
        }
        try (Connection c = connector.get()) {
            int linkId = ensureBrandTypeLink(c, brandId, typeId);
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE MODEL_STOCK SET stock = ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?")) {
                up.setInt(1, stock);
                up.setInt(2, linkId);
                up.setInt(3, modelId);
                up.setInt(4, sedeId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)")) {
                        ins.setInt(1, linkId);
                        ins.setInt(2, modelId);
                        ins.setInt(3, sedeId);
                        ins.setInt(4, stock);
                        ins.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set model stock", e);
        }
    }

    // Delta-based, unlike setModelStock()'s absolute value — read-then-write inside one explicit
    // transaction (not a separate getModelStock()+setModelStock() call pair) so two concurrent
    // adjustments to the same (model, sede) can't race and clobber each other. Reuses
    // setModelStock()'s "never go negative" rule: driving the result below zero throws
    // IllegalArgumentException, same message, so a Remito that would over-ship a source Sede's
    // stock fails loudly instead of silently going negative.
    @Override
    public void adjustModelStock(int modelId, int brandId, int typeId, int sedeId, int delta) {
        try (Connection c = connector.get()) {
            c.setAutoCommit(false);
            int linkId = ensureBrandTypeLink(c, brandId, typeId);
            int current;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT stock FROM MODEL_STOCK WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?")) {
                sel.setInt(1, linkId);
                sel.setInt(2, modelId);
                sel.setInt(3, sedeId);
                try (ResultSet rs = sel.executeQuery()) {
                    current = rs.next() ? rs.getInt(1) : 0;
                }
            }
            int updated = current + delta;
            if (updated < 0) {
                throw new IllegalArgumentException("El stock no puede ser negativo");
            }
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE MODEL_STOCK SET stock = ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?")) {
                up.setInt(1, updated);
                up.setInt(2, linkId);
                up.setInt(3, modelId);
                up.setInt(4, sedeId);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)")) {
                        ins.setInt(1, linkId);
                        ins.setInt(2, modelId);
                        ins.setInt(3, sedeId);
                        ins.setInt(4, updated);
                        ins.executeUpdate();
                    }
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to adjust model stock", e);
        }
    }

    // sedeId null means "every Sede combined" (summed) — SUPERADMIN's default Base de Datos view;
    // non-null scopes the rollup to one specific Sede.
    @Override
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

    @Override
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

    // Mirrors getModelsForBrandAndType()'s UNION-with-global-row shape — the global generic
    // model's stock must be summed too, since it's offered for every brand+type combination.
    @Override
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

    private Integer findBrandTypeLinkId(Connection c, int brandId, int typeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?")) {
            ps.setInt(1, typeId);
            ps.setInt(2, brandId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private Map<Integer, Integer> queryStockMap(String sql, Object... params) {
        Map<Integer, Integer> result = new HashMap<>();
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getInt(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load stock totals", e);
        }
        return result;
    }

    // Shared helpers — uniqueness on `name` holds across TYPE/BRAND/PROVIDER regardless of a
    // row's deprecated status (a name may only ever live on one row at a time), so every
    // add/rename above resolves against these before ever attempting an INSERT.
    private Integer findActiveIdExcluding(Connection c, String table, String name, int excludeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM " + table + " WHERE LOWER(name) = LOWER(?) AND id != ? AND deprecated = 0")) {
            ps.setString(1, name);
            ps.setInt(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private Integer findDeprecatedId(Connection c, String table, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM " + table + " WHERE LOWER(name) = LOWER(?) AND deprecated = 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private String findNameById(Connection c, String table, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM " + table + " WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void updateName(Connection c, String table, int id, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET name = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    private void setDeprecated(Connection c, String table, int id, boolean deprecated) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + table + " SET deprecated = ? WHERE id = ?")) {
            ps.setInt(1, deprecated ? 1 : 0);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }
}
