package com.bunshock.note_app_for_it_frontend.services.catalog;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteEquipmentServiceTest {

    @TempDir
    Path tempDir;

    private SqliteEquipmentService service;
    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:sqlite:" + tempDir.resolve("equipment-test.db").toAbsolutePath();
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
                    is_asset INTEGER NOT NULL DEFAULT 0,
                    requires_serial INTEGER NOT NULL DEFAULT 0,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE BRAND (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    deprecated INTEGER NOT NULL DEFAULT 0
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
                    brand_type_id INTEGER REFERENCES BRAND_TYPE_LINK(id),
                    name          TEXT NOT NULL,
                    deprecated    INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name)");
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX idx_model_global_generic_name ON MODEL(name) WHERE brand_type_id IS NULL");
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX idx_model_single_active_generic ON MODEL(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0");
            stmt.executeUpdate("""
                CREATE TABLE PROVIDER (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE SEDE (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE MODEL_STOCK (
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                    sede_id       INTEGER NOT NULL REFERENCES SEDE(id),
                    stock         INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (brand_type_id, model_id, sede_id)
                )""");
            stmt.executeUpdate("""
                CREATE TABLE SEDE_SHIPPING_INFO (
                    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                    sede_id            INTEGER NOT NULL REFERENCES SEDE(id),
                    destination_label  TEXT NOT NULL,
                    address            TEXT,
                    recipients         TEXT,
                    deprecated         INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX idx_sede_shipping_single_active ON SEDE_SHIPPING_INFO(sede_id) WHERE deprecated = 0");
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
        // Just LATITUDE — the global "Genérico / Otro" model (brand_type_id IS NULL) would also
        // be unioned in by getModelsForBrandAndType() if one existed, but this test's own schema
        // copy never seeds it (unlike the real app's insertDefaultData()/migration).
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

    private EquipmentType findType(String name) {
        return service.getAllTypes().stream()
            .filter(t -> t.getName().equals(name))
            .findFirst().orElseThrow();
    }

    // ── Rename cascade (Type/Brand) ─────────────────────────────────────────
    // Highest-risk new logic in the catalog-FK redesign: renaming a Type/Brand deprecates the
    // row every BRAND_TYPE_LINK using it points at, so an equivalent link (and every active
    // model under it) must be cloned forward onto the new id, or the active catalog would
    // silently lose everything configured under the old name.

    @Test
    void renameTypeClonesEveryLinkedBrandsActiveModelsToTheNewTypeId() throws SQLException {
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        service.addBrandForType("HP", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).stream()
            .filter(b -> b.getName().equals("DELL")).findFirst().orElseThrow();
        EquipmentBrand hp = service.getBrandsForType(notebook.getId()).stream()
            .filter(b -> b.getName().equals("HP")).findFirst().orElseThrow();
        service.addModel("LATITUDE", dell.getId(), notebook.getId());
        service.addModel("XPS", dell.getId(), notebook.getId());
        service.addModel("ELITEBOOK", hp.getId(), notebook.getId());

        int oldTypeId = notebook.getId();
        service.renameType(oldTypeId, "LAPTOP");

        assertTrue(isDeprecated("TYPE", oldTypeId));
        assertTrue(service.getAllTypes().stream().noneMatch(t -> t.getName().equals("NOTEBOOK")),
            "old name must no longer be offered as an active type");

        EquipmentType laptop = findType("LAPTOP");
        List<EquipmentBrand> laptopBrands = service.getBrandsForType(laptop.getId());
        assertEquals(2, laptopBrands.size());

        EquipmentBrand dellUnderLaptop = laptopBrands.stream()
            .filter(b -> b.getName().equals("DELL")).findFirst().orElseThrow();
        List<String> dellModels = service.getModelsForBrandAndType(dellUnderLaptop.getId(), laptop.getId())
            .stream().map(EquipmentModel::getName).toList();
        assertEquals(2, dellModels.size());
        assertTrue(dellModels.containsAll(List.of("LATITUDE", "XPS")));

        EquipmentBrand hpUnderLaptop = laptopBrands.stream()
            .filter(b -> b.getName().equals("HP")).findFirst().orElseThrow();
        List<String> hpModels = service.getModelsForBrandAndType(hpUnderLaptop.getId(), laptop.getId())
            .stream().map(EquipmentModel::getName).toList();
        assertEquals(1, hpModels.size());
        assertTrue(hpModels.contains("ELITEBOOK"));
    }

    @Test
    void renameBrandClonesActiveModelsAcrossEveryTypeItWasLinkedTo() throws SQLException {
        service.addType("NOTEBOOK", true);
        service.addType("MONITOR", true);
        EquipmentType notebook = findType("NOTEBOOK");
        EquipmentType monitor = findType("MONITOR");
        service.addBrandForType("DELL", notebook.getId());
        service.addBrandForType("DELL", monitor.getId());
        EquipmentBrand dellForNotebook = service.getBrandsForType(notebook.getId()).get(0);
        EquipmentBrand dellForMonitor = service.getBrandsForType(monitor.getId()).get(0);
        assertEquals(dellForNotebook.getId(), dellForMonitor.getId(),
            "addBrandForType() reuses the same BRAND row by name across different types");
        service.addModel("LATITUDE", dellForNotebook.getId(), notebook.getId());
        service.addModel("S22", dellForMonitor.getId(), monitor.getId());

        int oldBrandId = dellForNotebook.getId();
        service.renameBrand(oldBrandId, "DELL INC");

        assertTrue(isDeprecated("BRAND", oldBrandId));
        EquipmentBrand dellIncForNotebook = service.getBrandsForType(notebook.getId()).stream()
            .filter(b -> b.getName().equals("DELL INC")).findFirst().orElseThrow();
        EquipmentBrand dellIncForMonitor = service.getBrandsForType(monitor.getId()).stream()
            .filter(b -> b.getName().equals("DELL INC")).findFirst().orElseThrow();

        List<String> notebookModels = service.getModelsForBrandAndType(dellIncForNotebook.getId(), notebook.getId())
            .stream().map(EquipmentModel::getName).toList();
        assertTrue(notebookModels.contains("LATITUDE"));

        List<String> monitorModels = service.getModelsForBrandAndType(dellIncForMonitor.getId(), monitor.getId())
            .stream().map(EquipmentModel::getName).toList();
        assertTrue(monitorModels.contains("S22"));
    }

    @Test
    void renamingBackToADeprecatedNameReactivatesTheOriginalRowInsteadOfDuplicating() {
        service.addType("NOTEBOOK", true);
        int originalId = findType("NOTEBOOK").getId();

        service.renameType(originalId, "LAPTOP");
        int renamedId = findType("LAPTOP").getId();

        service.renameType(renamedId, "NOTEBOOK");
        EquipmentType reactivated = findType("NOTEBOOK");

        assertEquals(originalId, reactivated.getId(),
            "renaming back to the original name should reactivate the original row, not insert a new one");
        assertEquals(1, service.getAllTypes().size(),
            "only one active TYPE row should exist — no duplicate left behind");
    }

    // ── Generic brand never gets a BRAND_TYPE_LINK ──────────────────────────────
    // Picking the generic brand for a type used to lazily create a real link —
    // vestigial now that the generic brand is offered for every type via client-side synthesis
    // instead, and a surviving link makes that one type render it differently (sorted
    // alphabetically among real brands via the JOIN) than every other type (always appended
    // last via synthesis) — see DatabaseServiceMigrationTest's cleanup-migration test for the
    // other half of this fix.

    @Test
    void addBrandForTypeDoesNotLinkTheGenericBrand() throws SQLException {
        service.addType("NOTEBOOK", true);
        int notebookId = findType("NOTEBOOK").getId();

        service.addBrandForType("Genérico / Otro", notebookId);

        EquipmentBrand generic = service.getAllBrands().stream()
            .filter(b -> b.getName().equals("Genérico / Otro")).findFirst().orElseThrow();
        try (Connection c = DriverManager.getConnection(url);
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM BRAND_TYPE_LINK WHERE brand_id = ? AND type_id = ?")) {
            ps.setInt(1, generic.getId());
            ps.setInt(2, notebookId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "no BRAND_TYPE_LINK should be created for the generic brand");
            }
        }
        // A real brand added the normal way still gets linked as usual.
        service.addBrandForType("DELL", notebookId);
        assertEquals(1, service.getBrandsForType(notebookId).size());
        assertEquals("DELL", service.getBrandsForType(notebookId).get(0).getName());
    }

    // ── Global generic model (brand_type_id IS NULL) ────────────────────────────
    // The single "Genérico / Otro" model is not scoped to any BRAND_TYPE_LINK — it's a real
    // row, structurally identified by a null brand_type_id, offered for every brand+type
    // combination regardless of whether that combination has ever been linked.

    @Test
    void globalGenericModelIsOfferedForEveryBrandAndTypeCombination() throws SQLException {
        int genericId = insertGlobalGenericModel("Genérico / Otro");
        service.addType("NOTEBOOK", true);
        service.addType("MONITOR", true);
        service.addBrandForType("DELL", findType("NOTEBOOK").getId());
        EquipmentBrand dell = service.getBrandsForType(findType("NOTEBOOK").getId()).get(0);
        service.addModel("LATITUDE", dell.getId(), findType("NOTEBOOK").getId());

        List<EquipmentModel> dellNotebookModels =
            service.getModelsForBrandAndType(dell.getId(), findType("NOTEBOOK").getId());
        assertTrue(dellNotebookModels.stream().anyMatch(m -> m.getId() == genericId));

        // Never linked at all — no BRAND_TYPE_LINK for (brandId=999, typeId=MONITOR) exists,
        // yet the global model is still offered.
        List<EquipmentModel> neverLinked =
            service.getModelsForBrandAndType(999, findType("MONITOR").getId());
        assertEquals(1, neverLinked.size());
        assertEquals(genericId, neverLinked.get(0).getId());
        assertTrue(neverLinked.get(0).isGlobalGeneric());
    }

    @Test
    void removeModelRefusesToDeleteTheGlobalGenericModel() throws SQLException {
        int genericId = insertGlobalGenericModel("Genérico / Otro");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.removeModel(genericId));
        assertTrue(ex.getMessage().contains("genérico"));
        assertFalse(isDeprecated("MODEL", genericId));
    }

    @Test
    void renameModelOnTheGlobalGenericModelStaysGlobalAndDeprecatesTheOldRow() throws SQLException {
        int genericId = insertGlobalGenericModel("Genérico / Otro");
        service.addType("NOTEBOOK", true);
        service.addBrandForType("DELL", findType("NOTEBOOK").getId());
        EquipmentBrand dell = service.getBrandsForType(findType("NOTEBOOK").getId()).get(0);
        // A same-named real model under an actual link must not collide with the global rename
        // below — uniqueness for the global row is scoped to "brand_type_id IS NULL" only.
        service.addModel("Sin Especificar", dell.getId(), findType("NOTEBOOK").getId());

        service.renameModel(genericId, "Sin Especificar");

        assertTrue(isDeprecated("MODEL", genericId));
        List<EquipmentModel> stillOffered = service.getModelsForBrandAndType(999, 999);
        assertEquals(1, stillOffered.size());
        assertEquals("Sin Especificar", stillOffered.get(0).getName());
        assertTrue(stillOffered.get(0).isGlobalGeneric());
        assertNotEquals(genericId, stillOffered.get(0).getId());
    }

    private int insertGlobalGenericModel(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (NULL, ?, 0)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    // Stock is now scoped per Sede — every stock test needs a real SEDE row to key against,
    // since MODEL_STOCK.sede_id is a NOT NULL FK.
    private int insertSede(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO SEDE (name, deprecated) VALUES (?, 0)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    // ── Stock (MODEL_STOCK rollups) ─────────────────────────────────────────

    @Test
    void setModelStockThenGetModelStockRoundTrips() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).get(0);
        service.addModel("LATITUDE", dell.getId(), notebook.getId());
        EquipmentModel latitude = service.getModelsForBrandAndType(dell.getId(), notebook.getId()).get(0);

        assertEquals(0, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId),
            "no MODEL_STOCK row yet — should read as 0, not throw");

        service.setModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId, 14);
        assertEquals(14, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId));

        // Overwrite, not accumulate.
        service.setModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId, 5);
        assertEquals(5, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId));
    }

    // Two Sedes must carry fully independent numbers for the same Model.
    @Test
    void setModelStockIsIndependentPerSede() throws SQLException {
        int sedeA = insertSede("Campus Norte");
        int sedeB = insertSede("Campus Sur");
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).get(0);
        service.addModel("LATITUDE", dell.getId(), notebook.getId());
        EquipmentModel latitude = service.getModelsForBrandAndType(dell.getId(), notebook.getId()).get(0);

        service.setModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeA, 10);
        service.setModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeB, 3);

        assertEquals(10, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeA));
        assertEquals(3, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeB));
    }

    // The UI's own tfStock TextFormatter already blocks typing a
    // minus sign, but setModelStock() is the actual persistence boundary every caller goes
    // through, so it must reject a negative value directly too.
    @Test
    void setModelStockRejectsNegativeStock() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).get(0);
        service.addModel("LATITUDE", dell.getId(), notebook.getId());
        EquipmentModel latitude = service.getModelsForBrandAndType(dell.getId(), notebook.getId()).get(0);

        assertThrows(IllegalArgumentException.class, () ->
            service.setModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId, -1));
        // rejecting the call must not leave a stray MODEL_STOCK row behind
        assertEquals(0, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId));
    }

    // Remito's stock movement uses this instead of setModelStock() — decrement the source Sede,
    // increment the destination Sede, both by the same delta.
    @Test
    void adjustModelStockAppliesDeltaAcrossTwoSedes() throws SQLException {
        int sedeA = insertSede("Campus Norte");
        int sedeB = insertSede("Campus Sur");
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).get(0);
        service.addModel("LATITUDE", dell.getId(), notebook.getId());
        EquipmentModel latitude = service.getModelsForBrandAndType(dell.getId(), notebook.getId()).get(0);
        service.setModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeA, 5);

        service.adjustModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeA, -2);
        service.adjustModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeB, 2);

        assertEquals(3, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeA));
        assertEquals(2, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeB));
    }

    @Test
    void adjustModelStockRejectsDrivingStockNegative() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).get(0);
        service.addModel("LATITUDE", dell.getId(), notebook.getId());
        EquipmentModel latitude = service.getModelsForBrandAndType(dell.getId(), notebook.getId()).get(0);
        service.setModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId, 1);

        assertThrows(IllegalArgumentException.class, () ->
            service.adjustModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId, -2));
        assertEquals(1, service.getModelStock(latitude.getId(), dell.getId(), notebook.getId(), sedeId));
    }

    // ── Sede shipping info (Remito) ──────────────────────────────────────────

    @Test
    void getSedeShippingInfoReturnsEmptyWhenNotConfigured() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        assertTrue(service.getSedeShippingInfo(sedeId).isEmpty());
    }

    @Test
    void getSedeShippingInfoReturnsConfiguredRow() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        try (Connection c = DriverManager.getConnection(url);
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, address, recipients) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, sedeId);
            ps.setString(2, "CAU Recoleta");
            ps.setString(3, "Av. Siempreviva 742");
            ps.setString(4, "Juan Pérez");
            ps.executeUpdate();
        }

        var info = service.getSedeShippingInfo(sedeId);
        assertTrue(info.isPresent());
        assertEquals("CAU Recoleta", info.get().getDestinationLabel());
        assertEquals("Av. Siempreviva 742", info.get().getAddress());
        assertEquals("Juan Pérez", info.get().getRecipients());
    }

    @Test
    void getSedeShippingInfoIgnoresDeprecatedRowsAndReturnsOnlyTheActiveOne() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        try (Connection c = DriverManager.getConnection(url);
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, address, recipients, deprecated) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, sedeId);
            ps.setString(2, "Dirección vieja");
            ps.setString(3, "Calle Falsa 123");
            ps.setString(4, "Homero");
            ps.setInt(5, 1);
            ps.executeUpdate();

            ps.setInt(1, sedeId);
            ps.setString(2, "CAU Recoleta");
            ps.setString(3, "Av. Siempreviva 742");
            ps.setString(4, "Juan Pérez");
            ps.setInt(5, 0);
            ps.executeUpdate();
        }

        var info = service.getSedeShippingInfo(sedeId);
        assertTrue(info.isPresent());
        assertEquals("CAU Recoleta", info.get().getDestinationLabel());
    }

    @Test
    void getSedeIdsWithShippingInfoReturnsOnlySedesWithAnActiveRow() throws SQLException {
        int sedeConfigured = insertSede("Campus Norte");
        int sedeDeprecatedOnly = insertSede("Campus Sur");
        int sedeUnconfigured = insertSede("Campus Este");

        try (Connection c = DriverManager.getConnection(url);
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, deprecated) VALUES (?, ?, ?)")) {
            ps.setInt(1, sedeConfigured);
            ps.setString(2, "CAU Recoleta");
            ps.setInt(3, 0);
            ps.executeUpdate();

            ps.setInt(1, sedeDeprecatedOnly);
            ps.setString(2, "Dirección vieja");
            ps.setInt(3, 1);
            ps.executeUpdate();
        }

        var result = service.getSedeIdsWithShippingInfo();
        assertTrue(result.contains(sedeConfigured));
        assertFalse(result.contains(sedeDeprecatedOnly));
        assertFalse(result.contains(sedeUnconfigured));
    }

    @Test
    void setModelStockLazilyCreatesTheBrandTypeLinkWhenNoneExists() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        // The generic brand deliberately has no real BRAND_TYPE_LINK until something actually
        // needs one — setting stock for a Type it's never been linked to is exactly that case.
        service.addType("NOTEBOOK", true);
        int notebookId = findType("NOTEBOOK").getId();
        service.addBrandForType("Genérico / Otro", notebookId);
        EquipmentBrand generic = service.getAllBrands().stream()
            .filter(b -> b.getName().equals("Genérico / Otro")).findFirst().orElseThrow();
        int genericModelId = insertGlobalGenericModel("Genérico / Otro");

        assertDoesNotThrow(() ->
            service.setModelStock(genericModelId, generic.getId(), notebookId, sedeId, 3));
        assertEquals(3, service.getModelStock(genericModelId, generic.getId(), notebookId, sedeId));
    }

    @Test
    void getStockTotalsByTypeSumsAcrossBrands() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        service.addType("NOTEBOOK", true);
        int notebookId = findType("NOTEBOOK").getId();
        service.addBrandForType("DELL", notebookId);
        service.addBrandForType("HP", notebookId);
        EquipmentBrand dell = service.getBrandsForType(notebookId).stream()
            .filter(b -> b.getName().equals("DELL")).findFirst().orElseThrow();
        EquipmentBrand hp = service.getBrandsForType(notebookId).stream()
            .filter(b -> b.getName().equals("HP")).findFirst().orElseThrow();
        service.addModel("LATITUDE", dell.getId(), notebookId);
        service.addModel("ELITEBOOK", hp.getId(), notebookId);
        EquipmentModel latitude = service.getModelsForBrandAndType(dell.getId(), notebookId).get(0);
        EquipmentModel elitebook = service.getModelsForBrandAndType(hp.getId(), notebookId).get(0);

        service.setModelStock(latitude.getId(), dell.getId(), notebookId, sedeId, 4);
        service.setModelStock(elitebook.getId(), hp.getId(), notebookId, sedeId, 6);

        assertEquals(10, service.getStockTotalsByType(sedeId).getOrDefault(notebookId, 0));
    }

    // sedeId == null means "every Sede combined" — used by SUPERADMIN's default Base de Datos
    // view.
    @Test
    void getStockTotalsByTypeWithNullSedeCombinesEverySede() throws SQLException {
        int sedeA = insertSede("Campus Norte");
        int sedeB = insertSede("Campus Sur");
        service.addType("NOTEBOOK", true);
        int notebookId = findType("NOTEBOOK").getId();
        service.addBrandForType("DELL", notebookId);
        EquipmentBrand dell = service.getBrandsForType(notebookId).get(0);
        service.addModel("LATITUDE", dell.getId(), notebookId);
        EquipmentModel latitude = service.getModelsForBrandAndType(dell.getId(), notebookId).get(0);

        service.setModelStock(latitude.getId(), dell.getId(), notebookId, sedeA, 4);
        service.setModelStock(latitude.getId(), dell.getId(), notebookId, sedeB, 6);

        assertEquals(10, service.getStockTotalsByType(null).getOrDefault(notebookId, 0));
    }

    @Test
    void getStockTotalsByBrandForTypeSumsAcrossModels() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        service.addType("NOTEBOOK", true);
        int notebookId = findType("NOTEBOOK").getId();
        service.addBrandForType("DELL", notebookId);
        EquipmentBrand dell = service.getBrandsForType(notebookId).get(0);
        service.addModel("LATITUDE", dell.getId(), notebookId);
        service.addModel("XPS", dell.getId(), notebookId);
        List<EquipmentModel> dellModels = service.getModelsForBrandAndType(dell.getId(), notebookId);

        for (EquipmentModel m : dellModels) {
            service.setModelStock(m.getId(), dell.getId(), notebookId, sedeId, 3);
        }

        assertEquals(6, service.getStockTotalsByBrandForType(notebookId, sedeId).getOrDefault(dell.getId(), 0));
    }

    @Test
    void getStockTotalsByModelForBrandAndTypeIncludesGlobalGenericRow() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        int genericModelId = insertGlobalGenericModel("Genérico / Otro");
        service.addType("NOTEBOOK", true);
        int notebookId = findType("NOTEBOOK").getId();
        service.addBrandForType("DELL", notebookId);
        EquipmentBrand dell = service.getBrandsForType(notebookId).get(0);
        service.addModel("LATITUDE", dell.getId(), notebookId);
        EquipmentModel latitude = service.getModelsForBrandAndType(dell.getId(), notebookId).stream()
            .filter(m -> m.getName().equals("LATITUDE")).findFirst().orElseThrow();

        service.setModelStock(latitude.getId(), dell.getId(), notebookId, sedeId, 7);
        service.setModelStock(genericModelId, dell.getId(), notebookId, sedeId, 2);

        Map<Integer, Integer> totals =
            service.getStockTotalsByModelForBrandAndType(dell.getId(), notebookId, sedeId);
        assertEquals(7, totals.getOrDefault(latitude.getId(), 0));
        assertEquals(2, totals.getOrDefault(genericModelId, 0));
    }

    @Test
    void renamingModelCarriesStockForward() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        service.addType("NOTEBOOK", true);
        int notebookId = findType("NOTEBOOK").getId();
        service.addBrandForType("DELL", notebookId);
        EquipmentBrand dell = service.getBrandsForType(notebookId).get(0);
        service.addModel("LATITUDE", dell.getId(), notebookId);
        int oldModelId = service.getModelsForBrandAndType(dell.getId(), notebookId).get(0).getId();
        service.setModelStock(oldModelId, dell.getId(), notebookId, sedeId, 9);

        service.renameModel(oldModelId, "LATITUDE 5440");

        int newModelId = service.getModelsForBrandAndType(dell.getId(), notebookId).stream()
            .filter(m -> m.getName().equals("LATITUDE 5440")).findFirst().orElseThrow().getId();
        assertNotEquals(oldModelId, newModelId);
        assertEquals(9, service.getModelStock(newModelId, dell.getId(), notebookId, sedeId),
            "stock must follow the model to its new row id after a rename");
        assertEquals(0, service.getModelStock(oldModelId, dell.getId(), notebookId, sedeId),
            "the old (now-deprecated) row's stock should be moved, not duplicated");
    }

    @Test
    void renamingTypeCarriesStockForwardThroughCascade() throws SQLException {
        int sedeId = insertSede("Campus Norte");
        service.addType("NOTEBOOK", true);
        EquipmentType notebook = findType("NOTEBOOK");
        service.addBrandForType("DELL", notebook.getId());
        EquipmentBrand dell = service.getBrandsForType(notebook.getId()).get(0);
        service.addModel("LATITUDE", dell.getId(), notebook.getId());
        int oldModelId = service.getModelsForBrandAndType(dell.getId(), notebook.getId()).get(0).getId();
        service.setModelStock(oldModelId, dell.getId(), notebook.getId(), sedeId, 11);

        service.renameType(notebook.getId(), "LAPTOP");

        EquipmentType laptop = findType("LAPTOP");
        EquipmentBrand dellUnderLaptop = service.getBrandsForType(laptop.getId()).get(0);
        int newModelId = service.getModelsForBrandAndType(dellUnderLaptop.getId(), laptop.getId()).get(0).getId();

        assertEquals(11, service.getModelStock(newModelId, dellUnderLaptop.getId(), laptop.getId(), sedeId),
            "a Type rename cascades to clone the model, and its stock must follow the clone");
    }

    private boolean isDeprecated(String table, int id) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "SELECT deprecated FROM " + table + " WHERE id = ?")) {
            ps.setInt(1, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1) == 1;
            }
        }
    }
}
