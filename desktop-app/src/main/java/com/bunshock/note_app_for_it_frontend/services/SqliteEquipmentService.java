package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.SnValidation;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;

public class SqliteEquipmentService implements IEquipmentService {

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
                 "SELECT id, name, is_asset, requires_serial FROM TYPE ORDER BY name")) {
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
            WHERE btl.type_id = ? ORDER BY b.name
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
             ResultSet rs = c.createStatement().executeQuery("SELECT id, name FROM BRAND ORDER BY name")) {
            while (rs.next()) {
                result.add(new EquipmentBrand(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load brands", e);
        }
        return result;
    }

    @Override
    public List<EquipmentModel> getModelsForBrandAndType(int brandId, int typeId) {
        List<EquipmentModel> result = new ArrayList<>();
        String sql = """
            SELECT m.id, m.brand_type_id, m.name FROM MODEL m
            JOIN BRAND_TYPE_LINK btl ON btl.id = m.brand_type_id
            WHERE btl.type_id = ? AND btl.brand_id = ? ORDER BY m.name
            """;
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, typeId);
            ps.setInt(2, brandId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new EquipmentModel(rs.getInt("id"), rs.getInt("brand_type_id"), rs.getString("name")));
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
             ResultSet rs = c.createStatement().executeQuery("SELECT id, name FROM PROVIDER ORDER BY name")) {
            while (rs.next()) {
                result.add(new EquipmentProvider(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load providers", e);
        }
        return result;
    }

    @Override
    public Optional<SnValidation> getSnValidation(int modelId) {
        String sql = "SELECT * FROM SN_VALIDATION WHERE model_id = ? AND is_active = 1 LIMIT 1";
        try (Connection c = connector.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, modelId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new SnValidation(
                    rs.getInt("model_id"),
                    rs.getString("regex_pattern"),
                    rs.getString("description"),
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
            WHERE t.is_asset = 1
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
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO BRAND (name) VALUES (?) ON CONFLICT (name) DO NOTHING")) {
                ps.setString(1, brandName.trim());
                ps.executeUpdate();
            }
            int brandId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM BRAND WHERE name = ?")) {
                ps.setString(1, brandName.trim());
                ResultSet rs = ps.executeQuery();
                brandId = rs.getInt(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?) ON CONFLICT (type_id, brand_id) DO NOTHING")) {
                ps.setInt(1, typeId);
                ps.setInt(2, brandId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add brand for type", e);
        }
    }

    @Override
    public void renameType(int typeId, String newName) {
        try (Connection c = connector.get()) {
            String trimmed = newName.trim();
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT 1 FROM TYPE WHERE LOWER(name) = LOWER(?) AND id != ?")) {
                chk.setString(1, trimmed);
                chk.setInt(2, typeId);
                if (chk.executeQuery().next()) {
                    throw new IllegalArgumentException("Ya existe un tipo con ese nombre");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE TYPE SET name = ? WHERE id = ?")) {
                ps.setString(1, trimmed);
                ps.setInt(2, typeId);
                ps.executeUpdate();
            }
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
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT 1 FROM BRAND WHERE LOWER(name) = LOWER(?) AND id != ?")) {
                chk.setString(1, trimmed);
                chk.setInt(2, brandId);
                if (chk.executeQuery().next()) {
                    throw new IllegalArgumentException("Ya existe una marca con ese nombre");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE BRAND SET name = ? WHERE id = ?")) {
                ps.setString(1, trimmed);
                ps.setInt(2, brandId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rename brand", e);
        }
    }

    @Override
    public void renameModel(int modelId, String newName) {
        try (Connection c = connector.get()) {
            String trimmed = newName.trim();
            int brandTypeId;
            try (PreparedStatement sel = c.prepareStatement("SELECT brand_type_id FROM MODEL WHERE id = ?")) {
                sel.setInt(1, modelId);
                ResultSet rs = sel.executeQuery();
                if (!rs.next()) throw new IllegalArgumentException("Modelo no encontrado");
                brandTypeId = rs.getInt(1);
            }
            if (modelNameExists(c, brandTypeId, trimmed, modelId)) {
                throw new IllegalArgumentException("Ya existe un modelo con ese nombre para esta marca y tipo");
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE MODEL SET name = ? WHERE id = ?")) {
                ps.setString(1, trimmed);
                ps.setInt(2, modelId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rename model", e);
        }
    }

    private boolean modelNameExists(Connection c, int brandTypeId, String name, int excludeModelId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM MODEL WHERE brand_type_id = ? AND LOWER(name) = LOWER(?) AND id != ?")) {
            ps.setInt(1, brandTypeId);
            ps.setString(2, name);
            ps.setInt(3, excludeModelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public void addType(String name, boolean isAsset) {
        try (Connection c = connector.get()) {
            String trimmed = name.trim();
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT 1 FROM TYPE WHERE LOWER(name) = LOWER(?)")) {
                chk.setString(1, trimmed);
                if (chk.executeQuery().next()) {
                    throw new IllegalArgumentException("Ya existe un tipo con ese nombre");
                }
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO TYPE (name, is_asset) VALUES (?, ?)")) {
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
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("INSERT INTO BRAND (name) VALUES (?) ON CONFLICT (name) DO NOTHING")) {
            ps.setString(1, name.trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add brand", e);
        }
    }

    @Override
    public void addModel(String name, int brandId, int typeId) {
        try (Connection c = connector.get()) {
            int brandTypeId = ensureBrandTypeLink(c, brandId, typeId);
            String trimmed = name.trim();
            if (modelNameExists(c, brandTypeId, trimmed, -1)) {
                throw new IllegalArgumentException("Ya existe un modelo con ese nombre para esta marca y tipo");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO MODEL (brand_type_id, name) VALUES (?, ?)")) {
                ps.setInt(1, brandTypeId);
                ps.setString(2, trimmed);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add model", e);
        }
    }

    @Override
    public void addProvider(String name) {
        try (Connection c = connector.get()) {
            String trimmed = name.trim();
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT 1 FROM PROVIDER WHERE LOWER(name) = LOWER(?)")) {
                chk.setString(1, trimmed);
                if (chk.executeQuery().next()) {
                    throw new IllegalArgumentException("Ya existe un proveedor con ese nombre");
                }
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO PROVIDER (name) VALUES (?)")) {
                ins.setString(1, trimmed);
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add provider", e);
        }
    }

    private int ensureBrandTypeLink(Connection c, int brandId, int typeId) throws SQLException {
        PreparedStatement sel = c.prepareStatement(
            "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?");
        sel.setInt(1, typeId);
        sel.setInt(2, brandId);
        ResultSet rs = sel.executeQuery();
        if (rs.next()) return rs.getInt("id");

        PreparedStatement ins = c.prepareStatement(
            "INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)",
            PreparedStatement.RETURN_GENERATED_KEYS);
        ins.setInt(1, typeId);
        ins.setInt(2, brandId);
        ins.executeUpdate();
        return ins.getGeneratedKeys().getInt(1);
    }

    @Override
    public void removeType(int typeId) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("DELETE FROM TYPE WHERE id = ?")) {
            ps.setInt(1, typeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove type", e);
        }
    }

    @Override
    public void removeBrand(int brandId) {
        try (Connection c = connector.get()) {
            ResultSet rs = c.createStatement().executeQuery(
                "SELECT name FROM BRAND WHERE id = " + brandId);
            if (rs.next() && "Generic".equalsIgnoreCase(rs.getString("name"))) {
                throw new IllegalArgumentException("La marca 'Generic' no puede eliminarse");
            }
            PreparedStatement ps = c.prepareStatement("DELETE FROM BRAND WHERE id = ?");
            ps.setInt(1, brandId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove brand", e);
        }
    }

    @Override
    public void removeModel(int modelId) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("DELETE FROM MODEL WHERE id = ?")) {
            ps.setInt(1, modelId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove model", e);
        }
    }

    @Override
    public void removeProvider(int providerId) {
        try (Connection c = connector.get();
             PreparedStatement ps = c.prepareStatement("DELETE FROM PROVIDER WHERE id = ?")) {
            ps.setInt(1, providerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove provider", e);
        }
    }

    @Override
    public void renameProvider(int providerId, String newName) {
        try (Connection c = connector.get()) {
            String trimmed = newName.trim();
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT 1 FROM PROVIDER WHERE LOWER(name) = LOWER(?) AND id != ?")) {
                chk.setString(1, trimmed);
                chk.setInt(2, providerId);
                if (chk.executeQuery().next()) {
                    throw new IllegalArgumentException("Ya existe un proveedor con ese nombre");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE PROVIDER SET name = ? WHERE id = ?")) {
                ps.setString(1, trimmed);
                ps.setInt(2, providerId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rename provider", e);
        }
    }
}
