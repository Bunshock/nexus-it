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

import com.bunshock.note_app_for_it_frontend.models.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteHistoryServiceTest {

    @TempDir
    Path tempDir;

    private SqliteHistoryService service;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:sqlite:" + tempDir.resolve("history-test.db").toAbsolutePath();
        createSchema(url);
        service = new SqliteHistoryService(() -> {
            try { return DriverManager.getConnection(url); }
            catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    private void createSchema(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE TECHNICIAN_PROFILE (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    windows_username TEXT NOT NULL UNIQUE,
                    name             TEXT,
                    dni              TEXT,
                    email            TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at      TEXT NOT NULL,
                    profile_type    TEXT NOT NULL,
                    technician_id   INTEGER REFERENCES TECHNICIAN_PROFILE(id),
                    technician_name TEXT,
                    technician_dni  TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
                    note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    user_name       TEXT,
                    user_dni        TEXT,
                    user_email      TEXT,
                    motivo          TEXT,
                    failure_cause   TEXT,
                    failure_details TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_PROVEEDOR (
                    note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    provider_name    TEXT,
                    cuit             TEXT,
                    motivo           TEXT,
                    responsible_name TEXT,
                    responsible_dni  TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM (
                    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
                    note_id                 INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                    type_name               TEXT NOT NULL,
                    brand_name              TEXT,
                    model_name              TEXT,
                    serial_number           TEXT,
                    a_f                     TEXT,
                    quantity                INTEGER NOT NULL DEFAULT 1,
                    observations            TEXT,
                    is_asset                INTEGER NOT NULL DEFAULT 0,
                    glpi_status             TEXT NOT NULL DEFAULT 'N_A',
                    glpi_rejection_reason   TEXT,
                    glpi_status_updated_at  TEXT
                )""");
        }
    }

    private NoteReportItem assetItem(String type, String brand, String model, String serial, String af, GlpiStatus status) {
        NoteReportItem item = new NoteReportItem();
        item.setTypeName(type);
        item.setBrandName(brand);
        item.setModelName(model);
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
        r.setProfileType("Entrega - Proveedor");
        r.setCreatedAt(createdAt);
        r.setProviderName(providerName);
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
    void getByIdFallsBackToTechnicianIdJoinForLegacyNotes() throws SQLException {
        // Notes saved before technician_name/technician_dni existed only have technician_id set;
        // getById()/getFiltered() must still resolve the author via the old TECHNICIAN_PROFILE join.
        String url = "jdbc:sqlite:" + tempDir.resolve("history-test.db").toAbsolutePath();
        int reportId;
        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO TECHNICIAN_PROFILE (windows_username, name, dni) VALUES (?, ?, ?)")) {
                ps.setString(1, "legacyuser");
                ps.setString(2, "Legacy Tecnico");
                ps.setString(3, "20111222");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO NOTE_REPORT (created_at, profile_type, technician_id) VALUES (?, ?, 1)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, LocalDateTime.now().toString());
                ps.setString(2, "Entrega");
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                keys.next();
                reportId = keys.getInt(1);
            }
        }

        NoteReport byId = service.getById(reportId);
        assertEquals("Legacy Tecnico", byId.getAuthorName());
        assertEquals("20111222", byId.getAuthorDni());
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

    @Test
    void getPendingGlpiSyncReturnsOnlyReportsWithPendingItems() {
        service.save(userReport("Entrega", LocalDateTime.now(), "Pending User",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN1", "AF1", GlpiStatus.PENDING))));
        service.save(userReport("Entrega", LocalDateTime.now(), "Synced User",
            List.of(assetItem("NOTEBOOK", "DELL", "LATITUDE", "SN2", "AF2", GlpiStatus.SYNCED))));

        List<NoteReport> pending = service.getPendingGlpiSync();
        assertEquals(1, pending.size());
        assertEquals("Pending User", pending.get(0).getRecipientDisplay());
    }
}
