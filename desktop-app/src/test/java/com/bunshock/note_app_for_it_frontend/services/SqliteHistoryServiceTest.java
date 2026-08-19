package com.bunshock.note_app_for_it_frontend.services;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.history.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnAllocationBatch;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteHistoryServiceTest {

    @TempDir
    Path tempDir;

    private SqliteHistoryService service;
    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:sqlite:" + tempDir.resolve("history-test.db").toAbsolutePath();
        createSchema(url);
        service = new SqliteHistoryService(() -> {
            try { return DriverManager.getConnection(url); }
            catch (SQLException e) { throw new RuntimeException(e); }
        });
        // applyRemitoStockIfNeeded() resolves IEquipmentService via ServiceLocator — pointed at
        // this same temp database so Remito approval tests can assert real stock movement.
        ServiceLocator.getInstance().setEquipmentService(new SqliteEquipmentService(() -> {
            try { return DriverManager.getConnection(url); }
            catch (SQLException e) { throw new RuntimeException(e); }
        }));
    }

    private void createSchema(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE TYPE (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    name                TEXT NOT NULL UNIQUE,
                    is_asset            INTEGER NOT NULL DEFAULT 0,
                    requires_serial     INTEGER NOT NULL DEFAULT 0,
                    deprecated          INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE BRAND (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT NOT NULL UNIQUE,
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
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    name          TEXT NOT NULL,
                    deprecated    INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE PROVIDER (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT NOT NULL UNIQUE,
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
                CREATE TABLE SEDE (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT NOT NULL UNIQUE,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at        TEXT NOT NULL,
                    profile_type      TEXT NOT NULL,
                    technician_name   TEXT,
                    technician_dni    TEXT,
                    observations      TEXT,
                    sede              TEXT,
                    sede_id           INTEGER REFERENCES SEDE(id),
                    approval_status   TEXT NOT NULL DEFAULT 'PENDING',
                    stock_applied     INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT_REJECTION (
                    note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    rejection_reason TEXT NOT NULL
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
                    note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    user_name       TEXT,
                    user_dni        TEXT,
                    user_email      TEXT,
                    motivo          TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_DEVOLUCION_FALLA (
                    note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
                    failure_cause   TEXT NOT NULL,
                    failure_details TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_PRESTAMO_AREA_EVENTO (
                    note_report_id INTEGER PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
                    area_evento    TEXT NOT NULL
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_PROVEEDOR (
                    note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    provider_id      INTEGER NOT NULL REFERENCES PROVIDER(id),
                    cuit             TEXT,
                    motivo           TEXT,
                    responsible_name TEXT,
                    responsible_dni  TEXT
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
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REMITO_SEDE (
                    note_report_id    INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    shipping_info_id  INTEGER NOT NULL REFERENCES SEDE_SHIPPING_INFO(id)
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REMITO_OTHER (
                    note_report_id     INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    destination_label  TEXT NOT NULL,
                    address             TEXT,
                    recipients          TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    note_id      INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                    type_id      INTEGER NOT NULL REFERENCES TYPE(id),
                    brand_id     INTEGER NOT NULL REFERENCES BRAND(id),
                    model_id     INTEGER NOT NULL REFERENCES MODEL(id),
                    observations TEXT,
                    modifies_stock INTEGER NOT NULL DEFAULT 1
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_ASSET (
                    item_id       INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    serial_number TEXT,
                    a_f           TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_COUNTABLE (
                    item_id  INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    quantity INTEGER NOT NULL DEFAULT 1
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_STATUS_TRACKING (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id           INTEGER NOT NULL REFERENCES NOTE_ITEM(id),
                    tracking_type     TEXT NOT NULL CHECK (tracking_type IN ('GLPI', 'RETURN', 'GLPI_RETURN')),
                    status            TEXT NOT NULL,
                    rejection_reason  TEXT,
                    status_updated_at TEXT,
                    UNIQUE (item_id, tracking_type)
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_STOCK_EXCEPTION (
                    item_id INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    reason  TEXT NOT NULL
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_RETURN_ALLOCATION (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id    INTEGER NOT NULL REFERENCES NOTE_ITEM(id),
                    status     TEXT NOT NULL,
                    quantity   INTEGER NOT NULL,
                    reason     TEXT,
                    updated_at TEXT NOT NULL
                )""");
        }
    }

    // Resolve-or-create catalog rows for a (type, brand, model) triple, mirroring
    // SqliteEquipmentService's own upsert shape — NOTE_ITEM now stores real FKs, not text, so
    // every test item needs an actual catalog row behind it. Opens its own short-lived
    // connection off the shared `url` rather than the service's own connector, since this runs
    // during test setup, independent of the service under test.
    private int resolveOrCreate(String table, String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement sel = c.prepareStatement("SELECT id FROM " + table + " WHERE name = ?")) {
                sel.setString(1, name);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO " + table + " (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, name);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    return keys.getInt(1);
                }
            }
        }
    }

    private int resolveOrCreateBrandTypeLink(int typeId, int brandId) throws SQLException {
        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?")) {
                sel.setInt(1, typeId);
                sel.setInt(2, brandId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ins.setInt(1, typeId);
                ins.setInt(2, brandId);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    return keys.getInt(1);
                }
            }
        }
    }

    private int resolveOrCreateModel(int brandTypeId, String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT id FROM MODEL WHERE brand_type_id = ? AND name = ?")) {
                sel.setInt(1, brandTypeId);
                sel.setString(2, name);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO MODEL (brand_type_id, name) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ins.setInt(1, brandTypeId);
                ins.setString(2, name);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    return keys.getInt(1);
                }
            }
        }
    }

    private void seedItemIds(NoteReportItem item, String type, String brand, String model) {
        try {
            int typeId = resolveOrCreate("TYPE", type);
            int brandId = resolveOrCreate("BRAND", brand);
            int linkId = resolveOrCreateBrandTypeLink(typeId, brandId);
            int modelId = resolveOrCreateModel(linkId, model);
            item.setTypeId(typeId);
            item.setBrandId(brandId);
            item.setModelId(modelId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private NoteReportItem assetItem(String type, String brand, String model, String serial, String af, GlpiStatus status) {
        NoteReportItem item = new NoteReportItem();
        item.setTypeName(type);
        item.setBrandName(brand);
        item.setModelName(model);
        seedItemIds(item, type, brand, model);
        item.setSerialNumber(serial);
        item.setAf(af);
        item.setQuantity(1);
        item.setAsset(true);
        item.setGlpiStatus(status);
        return item;
    }

    private NoteReportItem countableItem(String type, String brand, String model, int qty) {
        NoteReportItem item = new NoteReportItem();
        item.setTypeName(type);
        item.setBrandName(brand);
        item.setModelName(model);
        seedItemIds(item, type, brand, model);
        item.setQuantity(qty);
        item.setAsset(false);
        item.setGlpiStatus(GlpiStatus.N_A);
        return item;
    }

    private NoteReport userReport(String profileType, LocalDateTime createdAt, String userName,
                                   List<NoteReportItem> items) {
        NoteReport r = new NoteReport();
        r.setProfileType(profileType);
        r.setCreatedAt(createdAt);
        r.setUserName(userName);
        r.setUserDni("30111222");
        r.setUserEmail("user@test.com");
        r.setMotivo("Alta");
        r.setItems(items);
        return r;
    }

    private NoteReport providerReport(LocalDateTime createdAt, String providerName, List<NoteReportItem> items) {
        NoteReport r = new NoteReport();
        r.setProfileType("ENTREGA - PROVEEDOR");
        r.setCreatedAt(createdAt);
        r.setProviderName(providerName);
        try {
            r.setProviderId(resolveOrCreate("PROVIDER", providerName));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        r.setCuit("30-12345678-9");
        r.setMotivo("Alta");
        r.setItems(items);
        return r;
    }

    // ── Save / getAll ────────────────────────────────────────────────────────

    @Test
    void savedUserReportAppearsInGetAll() {
        NoteReport r = userReport("Entrega", LocalDateTime.of(2026, 3, 1, 10, 0), "Juan Perez",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING),
                    countableItem("MOUSE", "GENIUS", "DX-120", 2)));
        int id = service.save(r);

        List<NoteReport> all = service.getAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).getId());
        assertEquals("Entrega", all.get(0).getProfileType());
        assertEquals("Juan Perez", all.get(0).getRecipientDisplay());
        assertEquals(1, all.get(0).getAssetItemCount());
        // getAll()/getFiltered() only ever populate the SQL-aggregated counts, never the full
        // items list (that's a separate, per-note query — see getById()) — so
        // getCountableItemCount() must come from its own aggregate column, not from
        // items.size() - assetItemCount, which silently evaluated to <= 0 here before this was
        // added (History's "Equipos" column consumes this same field, see HistoryController).
        assertEquals(1, all.get(0).getCountableItemCount());
    }

    @Test
    void getAllCountsCountableOnlyReportCorrectly() {
        NoteReport r = userReport("Entrega", LocalDateTime.of(2026, 3, 2, 10, 0), "Ana Diaz",
            List.of(countableItem("MOUSE", "GENIUS", "DX-120", 2),
                    countableItem("HEADSET", "GENIUS", "HS-04", 1)));
        service.save(r);

        NoteReport summary = service.getAll().get(0);
        assertEquals(0, summary.getAssetItemCount());
        assertEquals(2, summary.getCountableItemCount());
    }

    @Test
    void getByIdPersistsAndReturnsProviderResponsibleNameAndDni() {
        NoteReport r = providerReport(LocalDateTime.now(), "Proveedor SA", List.of());
        r.setResponsibleName("Carlos Ruiz");
        r.setResponsibleDni("25777333");
        int id = service.save(r);

        NoteReport full = service.getById(id);
        assertEquals("Carlos Ruiz", full.getResponsibleName());
        assertEquals("25777333", full.getResponsibleDni());
    }

    @Test
    void savedProviderReportUsesProviderNameAsRecipient() {
        NoteReport r = providerReport(LocalDateTime.of(2026, 3, 1, 10, 0), "Proveedor SA", List.of());
        service.save(r);

        List<NoteReport> all = service.getAll();
        assertEquals(1, all.size());
        assertEquals("Proveedor SA", all.get(0).getRecipientDisplay());
    }

    // ── getFiltered ───────────────────────────────────────────────────────────

    @Test
    void filtersByDateRange() {
        service.save(userReport("Entrega", LocalDateTime.of(2026, 1, 1, 10, 0), "Old User", List.of()));
        service.save(userReport("Entrega", LocalDateTime.of(2026, 6, 1, 10, 0), "New User", List.of()));

        HistoryFilter fromMay = new HistoryFilter();
        fromMay.setFromDate(LocalDate.of(2026, 5, 1));
        List<NoteReport> results = service.getFiltered(fromMay);
        assertEquals(1, results.size());
        assertEquals("New User", results.get(0).getRecipientDisplay());

        HistoryFilter untilJan = new HistoryFilter();
        untilJan.setToDate(LocalDate.of(2026, 1, 31));
        results = service.getFiltered(untilJan);
        assertEquals(1, results.size());
        assertEquals("Old User", results.get(0).getRecipientDisplay());
    }

    @Test
    void filtersByProfileType() {
        service.save(userReport("Entrega", LocalDateTime.now(), "A", List.of()));
        service.save(userReport("Devolución", LocalDateTime.now(), "B", List.of()));

        HistoryFilter filter = new HistoryFilter();
        filter.setProfileTypes(List.of("Devolución"));
        List<NoteReport> results = service.getFiltered(filter);

        assertEquals(1, results.size());
        assertEquals("B", results.get(0).getRecipientDisplay());
    }

    @Test
    void filterMatchesRowsRegardlessOfStoredCasing() {
        // Reproduces the real bug: seedHistoryData() stores nice-cased profile_type ("Devolución"),
        // but the live "Generar Nota" flow stores the raw ALL-CAPS ToggleButton text ("DEVOLUCIÓN").
        // A filter for one type must match rows stored either way — this is what
        // HistoryController.expandProfileTypeLabels() is responsible for feeding in.
        service.save(userReport("Devolución", LocalDateTime.now(), "SeedStyle", List.of()));
        service.save(userReport("DEVOLUCIÓN", LocalDateTime.now(), "LiveUiStyle", List.of()));

        HistoryFilter filter = new HistoryFilter();
        filter.setProfileTypes(List.of("Devolución", "DEVOLUCIÓN"));
        List<NoteReport> results = service.getFiltered(filter);

        assertEquals(2, results.size());
    }

    @Test
    void filtersByRecipientSearch() {
        service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez", List.of()));
        service.save(userReport("Entrega", LocalDateTime.now(), "Ana Diaz", List.of()));

        HistoryFilter filter = new HistoryFilter();
        filter.setRecipientSearch("perez");
        List<NoteReport> results = service.getFiltered(filter);

        assertEquals(1, results.size());
        assertEquals("Juan Perez", results.get(0).getRecipientDisplay());
    }

    @Test
    void filtersByAuthorSearch() {
        NoteReport byPerez = userReport("Entrega", LocalDateTime.now(), "Client A", List.of());
        byPerez.setAuthorName("Juan Perez");
        service.save(byPerez);

        NoteReport byDiaz = userReport("Entrega", LocalDateTime.now(), "Client B", List.of());
        byDiaz.setAuthorName("Ana Diaz");
        service.save(byDiaz);

        HistoryFilter filter = new HistoryFilter();
        filter.setAuthorSearch("perez");
        List<NoteReport> results = service.getFiltered(filter);

        assertEquals(1, results.size());
        assertEquals("Client A", results.get(0).getRecipientDisplay());
    }

    @Test
    void filtersByGlpiStatus() {
        service.save(userReport("Entrega", LocalDateTime.now(), "Pending User",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING))));
        service.save(userReport("Entrega", LocalDateTime.now(), "Synced User",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.SYNCED))));
        service.save(userReport("Entrega", LocalDateTime.now(), "No Asset User",
            List.of(countableItem("MOUSE", "GENIUS", "DX-120", 1))));

        HistoryFilter pending = new HistoryFilter();
        pending.setGlpiStatuses(List.of("PENDING"));
        List<NoteReport> pendingResults = service.getFiltered(pending);
        assertEquals(1, pendingResults.size());
        assertEquals("Pending User", pendingResults.get(0).getRecipientDisplay());

        HistoryFilter na = new HistoryFilter();
        na.setGlpiStatuses(List.of("N_A"));
        List<NoteReport> naResults = service.getFiltered(na);
        assertEquals(1, naResults.size());
        assertEquals("No Asset User", naResults.get(0).getRecipientDisplay());
    }

    // Reproduces a real reported bug: a Préstamo note's assets are saved with GlpiStatus.N_A
    // (not PENDING) by NotePreviewController/PrestamoNewLoanController — see CLAUDE.md's
    // "Préstamo assets are deliberately excluded from GLPI sync". matchesGlpiStatus()'s old
    // "N_A" case checked getAssetItemCount() == 0, which is false for such a note (it does have
    // an asset, just an N_A one) — silently excluding it from the "Sin GLPI" filter.
    @Test
    void filtersByGlpiStatusIncludesPrestamoNotesWhoseAssetsAreAllNA() {
        service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Loan User",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));

        HistoryFilter na = new HistoryFilter();
        na.setGlpiStatuses(List.of("N_A"));
        List<NoteReport> naResults = service.getFiltered(na);
        assertTrue(naResults.stream().anyMatch(r -> "Loan User".equals(r.getRecipientDisplay())),
            "A Préstamo note with an N_A asset must match the Sin GLPI filter");
    }

    @Test
    void filtersByItemTypeBrandAndModel() {
        service.save(userReport("Entrega", LocalDateTime.now(), "Monitor Owner",
            List.of(assetItem("MONITOR", "SAMSUNG", "S22", "SN1", "AF1", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "Notebook Owner",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.N_A))));

        HistoryFilter byType = new HistoryFilter();
        byType.setItemTypes(List.of("MONITOR"));
        assertEquals(1, service.getFiltered(byType).size());
        assertEquals("Monitor Owner", service.getFiltered(byType).get(0).getRecipientDisplay());

        HistoryFilter byBrandMismatch = new HistoryFilter();
        byBrandMismatch.setItemTypes(List.of("MONITOR"));
        byBrandMismatch.setItemBrands(List.of("DELL"));
        assertTrue(service.getFiltered(byBrandMismatch).isEmpty());
    }

    // ── Distinct item lookups ────────────────────────────────────────────────

    @Test
    void distinctItemTypesBrandsAndModelsNarrowProgressively() {
        service.save(userReport("Entrega", LocalDateTime.now(), "A",
            List.of(assetItem("MONITOR", "SAMSUNG", "S22", "SN1", "AF1", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "B",
            List.of(assetItem("MONITOR", "LG", "23EA53V", "SN2", "AF2", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "C",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN3", "AF3", GlpiStatus.N_A))));

        assertEquals(List.of("MONITOR", "NOTEBOOK"), service.getDistinctItemTypes());
        assertEquals(List.of("DELL", "LG", "SAMSUNG"), service.getDistinctItemBrands(null));
        assertEquals(List.of("LG", "SAMSUNG"), service.getDistinctItemBrands(List.of("MONITOR")));
        assertEquals(List.of("23EA53V"), service.getDistinctItemModels(List.of("MONITOR"), List.of("LG")));
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    void savedReportPersistsAuthorNameAndDniDirectly() {
        NoteReport r = userReport("Entrega", LocalDateTime.now(), "Juan Perez", List.of());
        r.setAuthorName("Marcos Tecnico");
        r.setAuthorDni("27555111");
        int id = service.save(r);

        NoteReport byId = service.getById(id);
        assertEquals("Marcos Tecnico", byId.getAuthorName());
        assertEquals("27555111", byId.getAuthorDni());

        NoteReport filtered = service.getAll().get(0);
        assertEquals("Marcos Tecnico", filtered.getAuthorName());
        assertEquals("27555111", filtered.getAuthorDni());
    }

    @Test
    void getByIdPersistsAndReturnsObservationsGenerales() {
        NoteReport r = userReport("Entrega", LocalDateTime.now(), "Juan Perez", List.of());
        r.setObservations("Equipo entregado con cargador adicional");
        int id = service.save(r);

        NoteReport byId = service.getById(id);
        assertEquals("Equipo entregado con cargador adicional", byId.getObservations());
    }

    @Test
    void getByIdPersistsAndReturnsSede() throws SQLException {
        NoteReport r = userReport("Entrega", LocalDateTime.now(), "Juan Perez", List.of());
        r.setSedeId(resolveOrCreate("SEDE", "Campus Test"));
        int id = service.save(r);

        NoteReport byId = service.getById(id);
        assertEquals("Campus Test", byId.getSede());
    }

    // Sede on the summary-row query (getAll()/getFiltered(), backing the History table and its
    // export) — a separate query path from getById() above, added so the History table's own
    // Sede column doesn't need a per-row detail fetch just to display it.
    @Test
    void getAllIncludesSedeOnSummaryRows() throws SQLException {
        NoteReport r = userReport("Entrega", LocalDateTime.now(), "Juan Perez", List.of());
        r.setSedeId(resolveOrCreate("SEDE", "Campus Norte"));
        service.save(r);

        assertEquals("Campus Norte", service.getAll().get(0).getSede());
    }

    @Test
    void getAllSedeIsBlankWhenNoSedeAssigned() {
        service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez", List.of()));
        assertEquals("", service.getAll().get(0).getSede());
    }

    @Test
    void getByIdReturnsFailureCauseAndDetailsForDevolucion() {
        NoteReport r = userReport("Devolución", LocalDateTime.now(), "Ana Diaz",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A)));
        r.setMotivo("Falla");
        r.setFailureCause("No enciende");
        r.setFailureDetails("Pantalla no responde");
        int id = service.save(r);

        NoteReport full = service.getById(id);
        assertEquals("No enciende", full.getFailureCause());
        assertEquals("Pantalla no responde", full.getFailureDetails());
    }

    @Test
    void getByIdReturnsFullReportWithItems() {
        int id = service.save(userReport("Entrega", LocalDateTime.of(2026, 4, 1, 12, 0), "Juan Perez",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING))));

        NoteReport full = service.getById(id);
        assertNotNull(full);
        assertEquals("Juan Perez", full.getUserName());
        assertEquals(1, full.getItems().size());
        NoteReportItem item = full.getItems().get(0);
        assertEquals("NOTEBOOK", item.getTypeName());
        assertEquals("SN1", item.getSerialNumber());
        assertEquals(GlpiStatus.PENDING, item.getGlpiStatus());
    }

    @Test
    void getByIdReturnsNullForUnknownId() {
        assertNull(service.getById(999));
    }

    // ── Most-used items ──────────────────────────────────────────────────────

    @Test
    void mostUsedTypeNamesReturnsTypesMeetingThreshold() {
        service.save(userReport("Entrega", LocalDateTime.now(), "A",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "B",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "C",
            List.of(assetItem("MONITOR", "SAMSUNG", "S22", "SN3", "AF3", GlpiStatus.N_A))));

        assertEquals(List.of("NOTEBOOK"), service.getMostUsedTypeNames(30, 2, 3));
    }

    @Test
    void mostUsedTypeNamesExcludesUsesOutsideWindow() {
        service.save(userReport("Entrega", LocalDateTime.now().minusDays(60), "A",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now().minusDays(60), "B",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.N_A))));

        assertTrue(service.getMostUsedTypeNames(30, 2, 3).isEmpty());
    }

    @Test
    void mostUsedTypeNamesOrderedByUsageCountDescendingAndRespectsLimit() {
        for (int i = 0; i < 2; i++) service.save(userReport("Entrega", LocalDateTime.now(), "M" + i,
            List.of(assetItem("MONITOR", "SAMSUNG", "S22", "SNm" + i, "AFm" + i, GlpiStatus.N_A))));
        for (int i = 0; i < 4; i++) service.save(userReport("Entrega", LocalDateTime.now(), "N" + i,
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SNn" + i, "AFn" + i, GlpiStatus.N_A))));

        assertEquals(List.of("NOTEBOOK", "MONITOR"), service.getMostUsedTypeNames(30, 2, 3));
        assertEquals(List.of("NOTEBOOK"), service.getMostUsedTypeNames(30, 2, 1));
    }

    @Test
    void mostUsedBrandNamesScopedToParentType() {
        service.save(userReport("Entrega", LocalDateTime.now(), "A",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "B",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "C",
            List.of(assetItem("MONITOR", "LG", "23EA53V", "SN3", "AF3", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "D",
            List.of(assetItem("MONITOR", "LG", "23EA53V", "SN4", "AF4", GlpiStatus.N_A))));

        assertEquals(List.of("DELL"), service.getMostUsedBrandNames("NOTEBOOK", 30, 2, 3));
        assertEquals(List.of("LG"), service.getMostUsedBrandNames("MONITOR", 30, 2, 3));
    }

    @Test
    void mostUsedModelNamesScopedToParentTypeAndBrand() {
        service.save(userReport("Entrega", LocalDateTime.now(), "A",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "B",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.N_A))));
        service.save(userReport("Entrega", LocalDateTime.now(), "C",
            List.of(assetItem("NOTEBOOK", "DELL", "XPS", "SN3", "AF3", GlpiStatus.N_A))));

        assertEquals(List.of("LATITUDE"), service.getMostUsedModelNames("NOTEBOOK", "DELL", 30, 2, 3));
    }

    // ── GLPI status update ────────────────────────────────────────────────────

    @Test
    void updateItemGlpiStatusPersistsStatusAndReason() {
        int id = service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING))));

        NoteReport before = service.getById(id);
        int itemId = before.getItems().get(0).getId();

        service.updateItemGlpiStatus(itemId, GlpiStatus.REJECTED, "Serial ilegible");

        NoteReport after = service.getById(id);
        NoteReportItem item = after.getItems().get(0);
        assertEquals(GlpiStatus.REJECTED, item.getGlpiStatus());
        assertEquals("Serial ilegible", item.getGlpiRejectionReason());
        assertNotNull(item.getGlpiStatusUpdatedAt());
    }

    // Exercises the INSERT-if-missing branch of updateItemGlpiStatus()'s UPDATE-then-INSERT
    // rewrite directly — this method used to be an ON CONFLICT(item_id) DO UPDATE upsert, which
    // is SQLite/PostgreSQL-only syntax SQL Server doesn't support at all (this class runs
    // unchanged against both). Every real caller only ever transitions an already-PENDING item
    // (so a NOTE_ITEM_GLPI_TRACKING row already exists), but the rewritten SQL must still be
    // correct for an item with no tracking row yet — an N_A asset has none, by design (row
    // absence IS N_A, see the NOTE_ITEM normalization notes in CLAUDE.md).
    @Test
    void updateItemGlpiStatusInsertsTrackingRowWhenNoneExistedYet() {
        int id = service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.updateItemGlpiStatus(itemId, GlpiStatus.SYNCED, null);

        NoteReportItem item = service.getById(id).getItems().get(0);
        assertEquals(GlpiStatus.SYNCED, item.getGlpiStatus());
        assertNotNull(item.getGlpiStatusUpdatedAt());
    }

    // ── Second GLPI dimension (synced back in) — Provider-Reparación assets only ─────────────
    // See NOTE_ITEM_GLPI_RETURN_TRACKING's own doc in DatabaseService: GLPI sync is one-way/
    // no-revert, so this is a genuinely separate tracking dimension, not a status transition on
    // the original NOTE_ITEM_GLPI_TRACKING row.

    @Test
    void updateItemGlpiReturnStatusPersistsStatusAndReason() {
        int id = service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.updateItemGlpiReturnStatus(itemId, GlpiStatus.PENDING, null);
        service.updateItemGlpiReturnStatus(itemId, GlpiStatus.REJECTED, "Datos incorrectos");

        NoteReportItem item = service.getById(id).getItems().get(0);
        assertEquals(GlpiStatus.REJECTED, item.getGlpiReturnStatus());
        assertEquals("Datos incorrectos", item.getGlpiReturnRejectionReason());
        assertNotNull(item.getGlpiReturnStatusUpdatedAt());
    }

    @Test
    void glpiReturnStatusDefaultsToNAUntilExplicitlySeeded() {
        int id = service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING))));

        assertEquals(GlpiStatus.N_A, service.getById(id).getItems().get(0).getGlpiReturnStatus());
    }

    // Regression coverage for LIST_BASE_SQL's COALESCE(igr.status, ig.status): once an item's
    // return is validated (seeding the second GLPI dimension as PENDING), THAT status must drive
    // the aggregate counts — even though the original sync-out already reached SYNCED — since
    // what matters after a return is whether GLPI now correctly reflects the item being back.
    @Test
    void returnStatusAggregatesUseSecondGlpiDimensionOnceSeeded() throws Exception {
        ConfigService.getInstance().load();
        NoteReport r = providerReport(LocalDateTime.now(), "Proveedor SA",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING)));
        r.setMotivo("Reparación");
        int id = service.save(r);
        int itemId = service.getById(id).getItems().get(0).getId();

        service.updateItemGlpiStatus(itemId, GlpiStatus.SYNCED, null);
        service.updateItemGlpiReturnStatus(itemId, GlpiStatus.PENDING, null);

        NoteReport summary = service.getFiltered(new HistoryFilter()).get(0);
        assertEquals(1, summary.getPendingItemCount());
        assertEquals(0, summary.getSyncedItemCount());
    }

    // A note still awaiting approval (or rejected) isn't a real GLPI-sync candidate yet — only an
    // APPROVED note's pending items count. Direct user requirement, enforced via
    // HistoryFilter.pendingGlpiSync()'s own approvalStatuses=APPROVED filter.
    @Test
    void getPendingGlpiSyncReturnsOnlyApprovedReportsWithPendingItems() {
        NoteReport approvedPending = userReport("Entrega", LocalDateTime.now(), "Pending User",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING)));
        approvedPending.setApprovalStatus("APPROVED");
        service.save(approvedPending);

        // approvalStatus left at its default ("PENDING") — must NOT count as GLPI-pending yet.
        service.save(userReport("Entrega", LocalDateTime.now(), "Still Awaiting Approval",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.PENDING))));

        NoteReport synced = userReport("Entrega", LocalDateTime.now(), "Synced User",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN3", "AF3", GlpiStatus.SYNCED)));
        synced.setApprovalStatus("APPROVED");
        service.save(synced);

        List<NoteReport> pending = service.getPendingGlpiSync();
        assertEquals(1, pending.size());
        assertEquals("Pending User", pending.get(0).getRecipientDisplay());
    }

    // ── Préstamo return status ────────────────────────────────────────────────

    @Test
    void insertItemsSetsReturnStatusPendingForPrestamoNotesBothAssetAndCountable() {
        NoteReport r = userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING),
                    countableItem("MOUSE", "GENIUS", "DX-120", 2)));
        int id = service.save(r);

        NoteReport full = service.getById(id);
        for (NoteReportItem item : full.getItems()) {
            assertEquals(ReturnStatus.PENDING, item.getReturnStatus());
        }
    }

    @Test
    void insertItemsLeavesReturnStatusNAForNonPrestamoNotes() {
        int id = service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING))));

        NoteReport full = service.getById(id);
        assertEquals(ReturnStatus.N_A, full.getItems().get(0).getReturnStatus());
    }

    // ── Note approval workflow ───────────────────────────────────────────────

    @Test
    void savedReportDefaultsToPendingApproval() {
        int id = service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez", List.of()));

        NoteReport full = service.getById(id);
        assertEquals("PENDING", full.getApprovalStatus());
        assertNull(full.getRejectionReason());
    }

    @Test
    void getFilteredWithNoExplicitApprovalStatusesReturnsEverything() {
        // The PENDING+APPROVED default lives in the controller (HistoryController.buildFilter()),
        // not the service — an explicitly empty/unset filter must still return every status.
        service.save(userReport("Entrega", LocalDateTime.now(), "A", List.of()));
        NoteReport rejected = userReport("Entrega", LocalDateTime.now(), "B", List.of());
        int rejectedId = service.save(rejected);
        service.updateNoteApprovalStatus(rejectedId, "REJECTED", "Nota creada por error");

        assertEquals(2, service.getFiltered(new HistoryFilter()).size());
    }

    @Test
    void getFilteredHonorsExplicitApprovalStatuses() {
        int pendingId = service.save(userReport("Entrega", LocalDateTime.now(), "A", List.of()));
        int approvedId = service.save(userReport("Entrega", LocalDateTime.now(), "B", List.of()));
        service.updateNoteApprovalStatus(approvedId, "APPROVED", null);
        int rejectedId = service.save(userReport("Entrega", LocalDateTime.now(), "C", List.of()));
        service.updateNoteApprovalStatus(rejectedId, "REJECTED", "Error de carga");

        HistoryFilter f = new HistoryFilter();
        f.setApprovalStatuses(List.of("PENDING", "APPROVED"));
        List<Integer> ids = service.getFiltered(f).stream().map(NoteReport::getId).toList();

        assertTrue(ids.contains(pendingId));
        assertTrue(ids.contains(approvedId));
        assertFalse(ids.contains(rejectedId));
    }

    @Test
    void updateNoteApprovalStatusPersists() {
        int id = service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez", List.of()));

        service.updateNoteApprovalStatus(id, "REJECTED", "Tipo de nota incorrecto");

        NoteReport full = service.getById(id);
        assertEquals("REJECTED", full.getApprovalStatus());
        assertEquals("Tipo de nota incorrecto", full.getRejectionReason());
    }

    @Test
    void getPendingApprovalReturnsOnlyPending() {
        int pendingId = service.save(userReport("Entrega", LocalDateTime.now(), "A", List.of()));
        int approvedId = service.save(userReport("Entrega", LocalDateTime.now(), "B", List.of()));
        service.updateNoteApprovalStatus(approvedId, "APPROVED", null);

        List<Integer> ids = service.getPendingApproval().stream().map(NoteReport::getId).toList();
        assertEquals(List.of(pendingId), ids);
    }

    // ── Provider conditional return tracking (config-driven via
    // AppConfig.returnableMotivosProveedor — config/app-config.json ships with
    // ["Garantía", "Reparación"], loaded here the same way several other tests in this suite
    // already load the real config file, e.g. UserNoteFallaPersistenceTest) ──────────────────

    @Test
    void saveMarksProviderItemsReturnPendingWhenMotivoIsReturnable() throws Exception {
        ConfigService.getInstance().load();
        NoteReport r = providerReport(LocalDateTime.now(), "Proveedor SA",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING),
                    countableItem("MOUSE", "GENIUS", "DX-120", 1)));
        r.setMotivo("Garantía");
        int id = service.save(r);

        NoteReport full = service.getById(id);
        for (NoteReportItem item : full.getItems()) {
            assertEquals(ReturnStatus.PENDING, item.getReturnStatus());
        }
    }

    @Test
    void saveLeavesProviderItemsNAWhenMotivoIsNotReturnable() throws Exception {
        ConfigService.getInstance().load();
        NoteReport r = providerReport(LocalDateTime.now(), "Proveedor SA",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING)));
        r.setMotivo("Otro");
        int id = service.save(r);

        NoteReport full = service.getById(id);
        assertEquals(ReturnStatus.N_A, full.getItems().get(0).getReturnStatus());
    }

    @Test
    void updateItemReturnStatusPersistsStatusAndReason() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING))));

        NoteReport before = service.getById(id);
        int itemId = before.getItems().get(0).getId();

        service.updateItemReturnStatus(itemId, ReturnStatus.LOST, "Equipo robado");

        NoteReport after = service.getById(id);
        NoteReportItem item = after.getItems().get(0);
        assertEquals(ReturnStatus.LOST, item.getReturnStatus());
        assertEquals("Equipo robado", item.getReturnRejectionReason());
        assertNotNull(item.getReturnStatusUpdatedAt());
    }

    // Same INSERT-if-missing rewrite, same SQL Server compatibility reason as
    // updateItemGlpiStatusInsertsTrackingRowWhenNoneExistedYet() above — for
    // NOTE_ITEM_RETURN_TRACKING instead of NOTE_ITEM_GLPI_TRACKING.
    @Test
    void updateItemReturnStatusInsertsTrackingRowWhenNoneExistedYet() {
        int id = service.save(userReport("Entrega", LocalDateTime.now(), "Juan Perez",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.updateItemReturnStatus(itemId, ReturnStatus.RETURNED, null);

        NoteReportItem item = service.getById(id).getItems().get(0);
        assertEquals(ReturnStatus.RETURNED, item.getReturnStatus());
        assertNotNull(item.getReturnStatusUpdatedAt());
    }

    @Test
    void returnStatusAggregatesComputeCorrectlyForMixedPrestamo() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING),
                    assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.PENDING),
                    countableItem("MOUSE", "GENIUS", "DX-120", 1))));

        NoteReport before = service.getById(id);
        List<NoteReportItem> items = before.getItems();
        service.updateItemReturnStatus(items.get(0).getId(), ReturnStatus.RETURNED, null);
        service.updateItemReturnStatus(items.get(1).getId(), ReturnStatus.LOST, "Perdido");

        NoteReport summary = service.getFiltered(new HistoryFilter()).get(0);
        assertEquals(1, summary.getReturnedItemCount());
        assertEquals(1, summary.getLostItemCount());
        assertEquals(1, summary.getReturnPendingItemCount());
    }

    // ── Countable partial return/lost allocation (NOTE_ITEM_RETURN_ALLOCATION) ────────────────

    @Test
    void allocateCountableReturnPersistsPartialQuantitiesAcrossMultipleCalls() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(countableItem("HEADSET", "LOGITECH", "H390", 5))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 3, null);
        service.allocateCountableReturn(itemId, ReturnStatus.LOST, 1, "Extraviado");

        NoteReportItem item = service.getById(id).getItems().get(0);
        assertEquals(3, item.getReturnedQuantity());
        assertEquals(1, item.getLostQuantity());
        assertEquals(1, item.getReturnPendingQuantity());
    }

    // Both LOST and RETURNED are surfaced per-batch, not just aggregated — each allocation is one
    // real event with its own single timestamp (LOST also has its own reason), which is what lets
    // the detail popups show a meaningful "when" for every batch instead of just a summed count.
    @Test
    void getByIdReturnsOneLostBatchPerAllocationEachWithItsOwnReason() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(countableItem("HEADSET", "LOGITECH", "H390", 5))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 2, null);
        service.allocateCountableReturn(itemId, ReturnStatus.LOST, 1, "Extraviado");
        service.allocateCountableReturn(itemId, ReturnStatus.LOST, 2, "Robado");

        NoteReportItem item = service.getById(id).getItems().get(0);
        assertEquals(2, item.getReturnedQuantity());
        assertEquals(3, item.getLostQuantity());
        assertEquals(0, item.getReturnPendingQuantity());

        List<ReturnAllocationBatch> batches = item.getLostBatches();
        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).getQuantity());
        assertEquals("Extraviado", batches.get(0).getReason());
        assertEquals(2, batches.get(1).getQuantity());
        assertEquals("Robado", batches.get(1).getReason());
    }

    @Test
    void getByIdReturnsOneReturnedBatchPerAllocation() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(countableItem("HEADSET", "LOGITECH", "H390", 5))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 2, null);
        service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 1, null);

        List<ReturnAllocationBatch> batches = service.getById(id).getItems().get(0).getReturnedBatches();
        assertEquals(2, batches.size());
        assertEquals(2, batches.get(0).getQuantity());
        assertEquals(1, batches.get(1).getQuantity());
    }

    @Test
    void getByIdReturnsEmptyLostBatchesWhenNothingHasBeenLost() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(countableItem("HEADSET", "LOGITECH", "H390", 5))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 5, null);

        assertTrue(service.getById(id).getItems().get(0).getLostBatches().isEmpty());
    }

    @Test
    void getByIdReturnsEmptyReturnedBatchesWhenNothingHasBeenReturned() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(countableItem("HEADSET", "LOGITECH", "H390", 5))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.allocateCountableReturn(itemId, ReturnStatus.LOST, 5, "Extraviado");

        assertTrue(service.getById(id).getItems().get(0).getReturnedBatches().isEmpty());
    }

    @Test
    void allocateCountableReturnRejectsQuantityExceedingPending() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(countableItem("HEADSET", "LOGITECH", "H390", 5))));
        int itemId = service.getById(id).getItems().get(0).getId();

        service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 4, null);

        assertThrows(IllegalArgumentException.class,
            () -> service.allocateCountableReturn(itemId, ReturnStatus.LOST, 2, "Perdido"));
    }

    @Test
    void allocateCountableReturnRejectsNonReturnedOrLostStatus() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(countableItem("HEADSET", "LOGITECH", "H390", 5))));
        int itemId = service.getById(id).getItems().get(0).getId();

        assertThrows(IllegalArgumentException.class,
            () -> service.allocateCountableReturn(itemId, ReturnStatus.PENDING, 1, null));
    }

    @Test
    void allocateCountableReturnRejectsZeroOrNegativeQuantity() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(countableItem("HEADSET", "LOGITECH", "H390", 5))));
        int itemId = service.getById(id).getItems().get(0).getId();

        assertThrows(IllegalArgumentException.class,
            () -> service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 0, null));
    }

    // Regression coverage for the LIST_BASE_SQL rewrite: an asset row still weighs 1 (unchanged),
    // but a partially-resolved countable must weigh its actual quantity split across
    // pending/returned/lost, not 1 indivisible unit — this is exactly the gap the user reported
    // (5 loaned headsets, 1 lost, should not force treating all 5 as still fully pending or
    // fully resolved).
    @Test
    void returnStatusAggregatesAccountForPartialCountableQuantities() {
        int id = service.save(userReport("PRÉSTAMO", LocalDateTime.now(), "Marta Ruiz",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING),
                    countableItem("HEADSET", "LOGITECH", "H390", 5))));

        NoteReport before = service.getById(id);
        List<NoteReportItem> items = before.getItems();
        int assetItemId = items.stream().filter(NoteReportItem::isAsset).findFirst().get().getId();
        int countableItemId = items.stream().filter(i -> !i.isAsset()).findFirst().get().getId();

        service.updateItemReturnStatus(assetItemId, ReturnStatus.RETURNED, null);
        service.allocateCountableReturn(countableItemId, ReturnStatus.RETURNED, 3, null);
        service.allocateCountableReturn(countableItemId, ReturnStatus.LOST, 1, "Extraviado");

        NoteReport summary = service.getFiltered(new HistoryFilter()).get(0);
        // asset RETURNED (1) + countable returned (3) = 4
        assertEquals(4, summary.getReturnedItemCount());
        // countable lost (1)
        assertEquals(1, summary.getLostItemCount());
        // countable still pending (5 - 3 - 1 = 1); asset is fully resolved, contributes 0
        assertEquals(1, summary.getReturnPendingItemCount());
    }

    // ── Remito ────────────────────────────────────────────────────────────────

    // destSedeId != null needs a real SEDE_SHIPPING_INFO row to reference — NOTE_REMITO_SEDE.
    // shipping_info_id is a mandatory FK now (see CLAUDE.md's Remito schema notes), same as
    // RemitoNoteController itself only ever offering a Sede that already has one configured.
    private NoteReport remitoReport(int sourceSedeId, Integer destSedeId, String destinationLabel,
                                     List<NoteReportItem> items) throws SQLException {
        NoteReport r = new NoteReport();
        r.setProfileType("REMITO DE ENVÍO");
        r.setCreatedAt(LocalDateTime.now());
        r.setSedeId(sourceSedeId);
        r.setDestinationSedeId(destSedeId);
        r.setDestinationLabel(destinationLabel);
        r.setAddress("Av. Test 123");
        r.setRecipients("Juan Pérez");
        if (destSedeId != null) {
            r.setShippingInfoId(insertShippingInfo(destSedeId, destinationLabel, "Av. Test 123", "Juan Pérez"));
        }
        r.setItems(items);
        return r;
    }

    private int insertShippingInfo(int sedeId, String label, String address, String recipients) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ins = c.prepareStatement(
                 "INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, address, recipients) VALUES (?, ?, ?, ?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ins.setInt(1, sedeId);
            ins.setString(2, label);
            ins.setString(3, address);
            ins.setString(4, recipients);
            ins.executeUpdate();
            try (ResultSet keys = ins.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    @Test
    void getByIdPersistsAndReturnsRemitoFields() throws SQLException {
        int sourceSedeId = resolveOrCreate("SEDE", "Campus Origen");
        int destSedeId = resolveOrCreate("SEDE", "Campus Destino");
        int id = service.save(remitoReport(sourceSedeId, destSedeId, "Campus Destino",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));

        NoteReport full = service.getById(id);
        assertEquals("REMITO DE ENVÍO", full.getProfileType());
        assertEquals(destSedeId, full.getDestinationSedeId());
        assertEquals("Campus Destino", full.getDestinationLabel());
        assertEquals("Av. Test 123", full.getAddress());
        assertEquals("Juan Pérez", full.getRecipients());
        assertFalse(full.isStockApplied());
    }

    // NOTE_REMITO has no motivo column of its own (a Remito has no natural "reason" the way
    // Entrega/Devolución/Proveedor do) — LIST_BASE_SQL's motivo column (used by both getAll()'s
    // summary rows and, via HistoryController.exportRowValues(), the CSV/XLSX export) special-
    // cases REMITO DE ENVÍO to a constant "Envío" instead, computed at query time rather than
    // persisted, so the global Historial de Notas table's Motivo column isn't left blank for
    // these rows.
    @Test
    void getAllShowsEnvioAsMotivoForRemitoSummaryRows() throws SQLException {
        int sourceSedeId = resolveOrCreate("SEDE", "Campus Origen");
        service.save(remitoReport(sourceSedeId, null, "CAU Recoleta",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));

        NoteReport summary = service.getAll().stream()
            .filter(r -> "REMITO DE ENVÍO".equals(r.getProfileType()))
            .findFirst().orElseThrow();
        assertEquals("Envío", summary.getMotivo());
    }

    @Test
    void remitoWithCustomDestinationPersistsNullDestinationSedeId() throws SQLException {
        int sourceSedeId = resolveOrCreate("SEDE", "Campus Origen");
        int id = service.save(remitoReport(sourceSedeId, null, "CAU Recoleta",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));

        assertNull(service.getById(id).getDestinationSedeId());
    }

    // Neither isPrestamo() nor isProviderReturnable() match a Remito's profile type, so
    // needsReturnTracking stays false — items keep whatever ReturnStatus the caller set (N_A),
    // not PENDING. Locks in that a Remito's items are never mistaken for a Préstamo loan.
    @Test
    void remitoItemsKeepNAReturnStatusNotPending() throws SQLException {
        int sourceSedeId = resolveOrCreate("SEDE", "Campus Origen");
        int id = service.save(remitoReport(sourceSedeId, null, "CAU Recoleta",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A))));

        assertEquals(ReturnStatus.N_A, service.getById(id).getItems().get(0).getReturnStatus());
    }

    @Test
    void approvingRemitoWithCatalogDestinationMovesStockBothWays() throws SQLException {
        int sourceSedeId = resolveOrCreate("SEDE", "Campus Origen");
        int destSedeId = resolveOrCreate("SEDE", "Campus Destino");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sourceSedeId, 5);

        int id = service.save(remitoReport(sourceSedeId, destSedeId, "Campus Destino", List.of(item)));
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(4, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sourceSedeId));
        assertEquals(1, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), destSedeId));
        assertTrue(service.getById(id).isStockApplied());
    }

    @Test
    void approvingRemitoWithCustomDestinationOnlyDecrementsSource() throws SQLException {
        int sourceSedeId = resolveOrCreate("SEDE", "Campus Origen");
        NoteReportItem item = countableItem("HEADSET", "LOGITECH", "H390", 3);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sourceSedeId, 10);

        int id = service.save(remitoReport(sourceSedeId, null, "CAU Recoleta", List.of(item)));
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(7, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sourceSedeId));
    }

    @Test
    void approvingRemitoTwiceDoesNotDoubleApplyStock() throws SQLException {
        int sourceSedeId = resolveOrCreate("SEDE", "Campus Origen");
        int destSedeId = resolveOrCreate("SEDE", "Campus Destino");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sourceSedeId, 5);

        int id = service.save(remitoReport(sourceSedeId, destSedeId, "Campus Destino", List.of(item)));
        service.updateNoteApprovalStatus(id, "APPROVED", null);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(4, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sourceSedeId));
        assertEquals(1, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), destSedeId));
    }

    @Test
    void approvingRemitoRejectsShippingMoreThanAvailableStock() throws SQLException {
        int sourceSedeId = resolveOrCreate("SEDE", "Campus Origen");
        int destSedeId = resolveOrCreate("SEDE", "Campus Destino");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sourceSedeId, 0);

        int id = service.save(remitoReport(sourceSedeId, destSedeId, "Campus Destino", List.of(item)));

        assertThrows(IllegalArgumentException.class, () -> service.updateNoteApprovalStatus(id, "APPROVED", null));
        assertFalse(service.getById(id).isStockApplied());
    }

    // ── Stock: every other note type (applyNoteStockIfNeeded's non-Remito dispatch) ────────────

    @Test
    void approvingEntregaDecrementsStockAtTechniciansSede() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 5);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(4, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
        assertTrue(service.getById(id).isStockApplied());
    }

    @Test
    void approvingDevolucionIncrementsStockAtTechniciansSede() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = countableItem("HEADSET", "LOGITECH", "H390", 2);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 3);

        NoteReport r = userReport("DEVOLUCIÓN", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(5, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    @Test
    void approvingPrestamoDecrementsStockAtTechniciansSede() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 2);

        NoteReport r = userReport("PRÉSTAMO", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(1, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    @Test
    void approvingProviderNoteDecrementsStockAtTechniciansSede() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 1);

        NoteReport r = providerReport(LocalDateTime.now(), "Proveedor Test", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(0, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    @Test
    void approvingEntregaTwiceDoesNotDoubleDecrementStock() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 5);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(4, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    @Test
    void approvingEntregaRejectsRequestingMoreThanAvailableStock() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 0);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);

        assertThrows(IllegalArgumentException.class, () -> service.updateNoteApprovalStatus(id, "APPROVED", null));
        assertFalse(service.getById(id).isStockApplied());
    }

    // aggregateItemQuantities() merges by (modelId, brandId, typeId) before moving stock — the
    // same model can appear on more than one NOTE_ITEM row (e.g. two individually-serialized
    // units of one model), and only their combined total should ever hit MODEL_STOCK.
    @Test
    void approvingEntregaWithSameModelOnMultipleRowsAggregatesTheirCombinedQuantity() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item1 = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);
        NoteReportItem item2 = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.PENDING);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item1.getModelId(), item1.getBrandId(), item1.getTypeId(), sedeId, 5);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(item1, item2));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        // Two rows of the same model must decrement by their combined total (2), not be treated
        // as two unrelated single-unit moves that could each independently pass an availability
        // check a combined check would have failed.
        assertEquals(3, equipment.getModelStock(item1.getModelId(), item1.getBrandId(), item1.getTypeId(), sedeId));
    }

    @Test
    void approvingEntregaWithMultipleDifferentModelsDecrementsEachIndependently() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem asset = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);
        NoteReportItem countable = countableItem("HEADSET", "LOGITECH", "H390", 3);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(asset.getModelId(), asset.getBrandId(), asset.getTypeId(), sedeId, 4);
        equipment.setModelStock(countable.getModelId(), countable.getBrandId(), countable.getTypeId(), sedeId, 10);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(asset, countable));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(3, equipment.getModelStock(asset.getModelId(), asset.getBrandId(), asset.getTypeId(), sedeId));
        assertEquals(7, equipment.getModelStock(countable.getModelId(), countable.getBrandId(), countable.getTypeId(), sedeId));
    }

    // The insufficient-stock guard in applyDirectionalStock() compares a countable's requested
    // quantity (not just a flat "1 per row" like an asset) against availability — previously only
    // ever exercised with an asset (an implicit quantity of 1 against 0 available).
    @Test
    void approvingEntregaRejectsWhenCountableQuantityExceedsAvailableStock() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = countableItem("HEADSET", "LOGITECH", "H390", 5);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 2);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);

        assertThrows(IllegalArgumentException.class, () -> service.updateNoteApprovalStatus(id, "APPROVED", null));
        assertEquals(2, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
        assertFalse(service.getById(id).isStockApplied());
    }

    // ── Stock: return/receive crediting (updateItemReturnStatus / allocateCountableReturn) ─────

    @Test
    void returningAssetItemCreditsStockBackOnce() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 3);

        NoteReport r = userReport("PRÉSTAMO", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int reportId = service.save(r);
        int itemId = service.getById(reportId).getItems().get(0).getId();

        service.updateItemReturnStatus(itemId, ReturnStatus.RETURNED, null);
        assertEquals(4, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));

        // Re-calling on an already-Returned item must not credit stock a second time.
        service.updateItemReturnStatus(itemId, ReturnStatus.RETURNED, null);
        assertEquals(4, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    @Test
    void markingAssetItemLostDoesNotCreditStock() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 3);

        NoteReport r = userReport("PRÉSTAMO", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int reportId = service.save(r);
        int itemId = service.getById(reportId).getItems().get(0).getId();

        service.updateItemReturnStatus(itemId, ReturnStatus.LOST, "No devuelto");
        assertEquals(3, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    @Test
    void allocatingCountableReturnCreditsStockOnEveryCall() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = countableItem("HEADSET", "LOGITECH", "H390", 5);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 0);

        NoteReport r = userReport("PRÉSTAMO", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int reportId = service.save(r);
        int itemId = service.getById(reportId).getItems().get(0).getId();

        service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 3, null);
        assertEquals(3, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));

        service.allocateCountableReturn(itemId, ReturnStatus.LOST, 2, "Perdido");
        assertEquals(3, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    // ── modifies_stock: ItemDialogController's "Modifica stock" exception checkbox ─────────────

    @Test
    void getByIdPersistsAndReturnsModifiesStock() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);
        item.setModifiesStock(false);
        item.setModifiesStockReason("Equipo ya estaba en poder del usuario, se formaliza la entrega.");

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);

        NoteReportItem loaded = service.getById(id).getItems().get(0);
        assertFalse(loaded.isModifiesStock());
        assertEquals("Equipo ya estaba en poder del usuario, se formaliza la entrega.", loaded.getModifiesStockReason());
    }

    @Test
    void getByIdReturnsNullModifiesStockReasonWhenItemModifiesStockNormally() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);

        NoteReportItem loaded = service.getById(id).getItems().get(0);
        assertNull(loaded.getModifiesStockReason());
    }

    @Test
    void itemsDefaultToModifyingStockWhenNotExplicitlySet() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);

        assertTrue(service.getById(id).getItems().get(0).isModifiesStock());
    }

    @Test
    void approvingEntregaSkipsStockForItemsFlaggedAsNotModifyingStock() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem normal = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING);
        NoteReportItem exempt = assetItem("MOUSE", "LOGITECH", "M100", "SN2", "AF2", GlpiStatus.PENDING);
        exempt.setModifiesStock(false);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(normal.getModelId(), normal.getBrandId(), normal.getTypeId(), sedeId, 5);
        equipment.setModelStock(exempt.getModelId(), exempt.getBrandId(), exempt.getTypeId(), sedeId, 5);

        NoteReport r = userReport("ENTREGA", LocalDateTime.now(), "Juan Perez", List.of(normal, exempt));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(4, equipment.getModelStock(normal.getModelId(), normal.getBrandId(), normal.getTypeId(), sedeId));
        assertEquals(5, equipment.getModelStock(exempt.getModelId(), exempt.getBrandId(), exempt.getTypeId(), sedeId));
    }

    @Test
    void approvingDevolucionSkipsStockForItemFlaggedAsNotModifyingStock() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = countableItem("HEADSET", "LOGITECH", "H390", 2);
        item.setModifiesStock(false);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 3);

        NoteReport r = userReport("DEVOLUCIÓN", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int id = service.save(r);
        service.updateNoteApprovalStatus(id, "APPROVED", null);

        assertEquals(3, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    @Test
    void returningItemFlaggedAsNotModifyingStockDoesNotCreditStockBack() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.N_A);
        item.setModifiesStock(false);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 3);

        NoteReport r = userReport("PRÉSTAMO", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int reportId = service.save(r);
        int itemId = service.getById(reportId).getItems().get(0).getId();

        service.updateItemReturnStatus(itemId, ReturnStatus.RETURNED, null);
        assertEquals(3, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }

    @Test
    void allocatingCountableReturnForItemFlaggedAsNotModifyingStockDoesNotCreditStock() throws SQLException {
        int sedeId = resolveOrCreate("SEDE", "Campus Test");
        NoteReportItem item = countableItem("HEADSET", "LOGITECH", "H390", 5);
        item.setModifiesStock(false);
        IEquipmentService equipment = ServiceLocator.getInstance().getEquipmentService();
        equipment.setModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId, 0);

        NoteReport r = userReport("PRÉSTAMO", LocalDateTime.now(), "Juan Perez", List.of(item));
        r.setSedeId(sedeId);
        int reportId = service.save(r);
        int itemId = service.getById(reportId).getItems().get(0).getId();

        service.allocateCountableReturn(itemId, ReturnStatus.RETURNED, 3, null);
        assertEquals(0, equipment.getModelStock(item.getModelId(), item.getBrandId(), item.getTypeId(), sedeId));
    }
}
