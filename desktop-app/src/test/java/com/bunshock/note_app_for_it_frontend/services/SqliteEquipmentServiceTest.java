package com.bunshock.note_app_for_it_frontend.services;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteEquipmentServiceTest {

    @TempDir
    Path tempDir;

    private SqliteEquipmentService service;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:sqlite:" + tempDir.resolve("equipment-test.db").toAbsolutePath();
        createSchema(url);
        service = new SqliteEquipmentService(() -> {
            try { return DriverManager.getConnection(url); }
            catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    private void createSchema(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE TYPE (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    is_asset INTEGER NOT NULL DEFAULT 1,
                    requires_serial INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE BRAND (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""");
            stmt.executeUpdate("""
                CREATE TABLE BRAND_TYPE_LINK (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    type_id  INTEGER NOT NULL REFERENCES TYPE(id),
                    brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                    UNIQUE(type_id, brand_id)
                )""");
            stmt.executeUpdate("""
                CREATE TABLE MODEL (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    name          TEXT NOT NULL
                )""");
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name)");
            stmt.executeUpdate("""
                CREATE TABLE PROVIDER (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""");
        }
    }

    // ── Type ─────────────────────────────────────────────────────────────────

    @Test
    void addTypeRejectsCaseInsensitiveDuplicate() {
        service.addType("NOTEBOOK", true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.addType("notebook", true));
        assertTrue(ex.getMessage().contains("Ya existe"));

        assertEquals(1, service.getAllTypes().size());
    }

    @Test
    void renameTypeRejectsCaseInsensitiveDuplicate() {
        service.addType("NOTEBOOK", true);
        service.addType("MONITOR", true);
        EquipmentType monitor = findType("MONITOR");

        assertThrows(IllegalArgumentException.class, () -> service.renameType(monitor.getId(), "notebook"));
    }

    @Test
    void renameTypeToItsOwnNameDoesNotThrow() {
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");

        assertDoesNotThrow(() -> service.renameType(notebook.getId(), "NOTEBOOK"));
    }

    // ── Brand ────────────────────────────────────────────────────────────────

    @Test
    void renameBrandRejectsCaseInsensitiveDuplicate() {
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        service.addBrandForType("LENOVO", notebook.getId());
        EquipmentBrand lenovo = service.getAllBrands().stream()
            .filter(b -> b.getName().equals("LENOVO")).findFirst().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> service.renameBrand(lenovo.getId(), "dell"));
    }

    @Test
    void addBrandForTypeReusesExistingBrandAcrossDifferentTypes() {
        // Reusing a brand name across multiple types is intentional (e.g. DELL makes both
        // notebooks and monitors) — this must NOT be treated as a duplicate-name error.
        service.addType("NOTEBOOK", true);
        service.addType("MONITOR", true);
        EquipmentType notebook = findType("NOTEBOOK");
        EquipmentType monitor = findType("MONITOR");

        service.addBrandForType("DELL", notebook.getId());
        assertDoesNotThrow(() -> service.addBrandForType("DELL", monitor.getId()));

        assertEquals(1, service.getAllBrands().size());
        assertEquals(1, service.getBrandsForType(notebook.getId()).size());
        assertEquals(1, service.getBrandsForType(monitor.getId()).size());
    }

    @Test
    void getAllBrandsReturnsEveryBrandRegardlessOfType() {
        service.addType("NOTEBOOK", true);
        service.addType("MONITOR", true);
        service.addBrandForType("DELL", findType("NOTEBOOK").getId());
        service.addBrandForType("SAMSUNG", findType("MONITOR").getId());

        List<String> names = service.getAllBrands().stream().map(EquipmentBrand::getName).sorted().toList();
        assertEquals(List.of("DELL", "SAMSUNG"), names);
    }

    // ── Model ────────────────────────────────────────────────────────────────

    @Test
    void addModelRejectsDuplicateWithinSameBrandAndType() {
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).get(0);

        service.addModel("LATITUDE", dell.getId(), notebook.getId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.addModel("latitude", dell.getId(), notebook.getId()));
        assertTrue(ex.getMessage().contains("Ya existe"));
        assertEquals(1, service.getModelsForBrandAndType(dell.getId(), notebook.getId()).size());
    }

    @Test
    void addModelAllowsSameNameUnderDifferentBrandOrType() {
        // "Otro / Genérico" (and similar) is intentionally reused across many brand+type
        // combinations — uniqueness must be scoped to (brand_type_id), not global.
        service.addType("NOTEBOOK", true);
        service.addType("MONITOR", true);
        service.addBrandForType("DELL", findType("NOTEBOOK").getId());
        service.addBrandForType("DELL", findType("MONITOR").getId());
        EquipmentBrand dellForNotebook = service.getBrandsForType(findType("NOTEBOOK").getId()).get(0);
        EquipmentBrand dellForMonitor = service.getBrandsForType(findType("MONITOR").getId()).get(0);

        service.addModel("Generic", dellForNotebook.getId(), findType("NOTEBOOK").getId());
        assertDoesNotThrow(() ->
            service.addModel("Generic", dellForMonitor.getId(), findType("MONITOR").getId()));
    }

    @Test
    void renameModelRejectsDuplicateWithinSameBrandAndType() {
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).get(0);
        service.addModel("LATITUDE", dell.getId(), notebook.getId());
        service.addModel("XPS", dell.getId(), notebook.getId());
        int xpsId = service.getModelsForBrandAndType(dell.getId(), notebook.getId()).stream()
            .filter(m -> m.getName().equals("XPS")).findFirst().orElseThrow().getId();

        assertThrows(IllegalArgumentException.class, () -> service.renameModel(xpsId, "latitude"));
    }

    // ── Provider ─────────────────────────────────────────────────────────────

    @Test
    void addProviderRejectsCaseInsensitiveDuplicate() {
        service.addProvider("TechCorp S.A.");

        assertThrows(IllegalArgumentException.class, () -> service.addProvider("techcorp s.a."));
        assertEquals(1, service.getAllProviders().size());
    }

    @Test
    void renameProviderRejectsCaseInsensitiveDuplicate() {
        service.addProvider("TechCorp S.A.");
        service.addProvider("Distribuidora Sur");
        int distribuidoraId = service.getAllProviders().stream()
            .filter(p -> p.getName().equals("Distribuidora Sur")).findFirst().orElseThrow().getId();

        assertThrows(IllegalArgumentException.class,
            () -> service.renameProvider(distribuidoraId, "techcorp s.a."));
    }

    private EquipmentType findType(String name) {
        return service.getAllTypes().stream()
            .filter(t -> t.getName().equals(name))
            .findFirst().orElseThrow();
    }
}
