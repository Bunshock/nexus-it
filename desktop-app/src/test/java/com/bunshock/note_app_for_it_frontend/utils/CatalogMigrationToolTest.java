package com.bunshock.note_app_for_it_frontend.utils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

// CatalogMigrationTool is normally run against a real SQL Server target, which isn't available
// in this test environment (no test infrastructure for it anywhere in this suite — see
// RemoteDatabaseServiceTest, which only ever exercises unreachable-host failure paths). Its
// migrate*() methods are plain check-then-insert-or-update SQL (SELECT to find an existing row,
// then INSERT or UPDATE — no SQL Server-specific MERGE/OUTPUT syntax), which SQLite executes
// identically, so a second, separate SQLite database standing in for "the remote database" still
// exercises the real upsert/id-remapping logic as literally written — this isn't a mock, it's the
// actual code path with a same-dialect stand-in on the other end.
class CatalogMigrationToolTest {

    @TempDir
    Path tempDir;

    private Connection source;
    private Connection target;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("source.db"));
        target = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("target.db"));
        createCatalogSchema(source);
        createCatalogSchema(target);

        // Seed the target with a row that consumes id=1 before migration ever runs, so a
        // migrated row landing on the SAME id as its source row would be a coincidence the test
        // can't trust — the target's ids are guaranteed to differ from the source's from the
        // start, the same way a real SQL Server IDENTITY column starts independently of whatever
        // ids SQLite happened to assign locally.
        try (Statement s = target.createStatement()) {
            s.executeUpdate("INSERT INTO TYPE (name, is_asset, requires_serial) VALUES ('Dummy', 1, 0)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        // SQLite's JDBC driver holds the file open until the Connection is closed — without
        // this, @TempDir's cleanup fails on Windows because the .db files are still locked by
        // this JVM, even though the test itself already passed.
        source.close();
        target.close();
    }

    private void createCatalogSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE TYPE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    is_asset INTEGER NOT NULL DEFAULT 1,
                    requires_serial INTEGER NOT NULL DEFAULT 0,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            s.executeUpdate("""
                CREATE TABLE BRAND (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            s.executeUpdate("""
                CREATE TABLE BRAND_TYPE_LINK (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type_id INTEGER NOT NULL REFERENCES TYPE(id),
                    brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                    UNIQUE(type_id, brand_id)
                )""");
            s.executeUpdate("""
                CREATE TABLE MODEL (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    brand_type_id INTEGER REFERENCES BRAND_TYPE_LINK(id),
                    name TEXT NOT NULL,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            s.executeUpdate("CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name)");
            s.executeUpdate("CREATE UNIQUE INDEX idx_model_global_generic_name ON MODEL(name) WHERE brand_type_id IS NULL");
            s.executeUpdate("""
                CREATE TABLE SN_VALIDATION (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    model_id INTEGER NOT NULL REFERENCES MODEL(id),
                    regex_pattern TEXT,
                    is_active INTEGER NOT NULL DEFAULT 1
                )""");
            s.executeUpdate("""
                CREATE TABLE PROVIDER (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            s.executeUpdate("""
                CREATE TABLE SEDE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            s.executeUpdate("""
                CREATE TABLE MODEL_STOCK (
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                    sede_id       INTEGER NOT NULL REFERENCES SEDE(id),
                    stock         INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (brand_type_id, model_id, sede_id)
                )""");
        }
    }

    private void seedSourceCatalog() throws SQLException {
        try (Statement s = source.createStatement()) {
            s.executeUpdate("INSERT INTO TYPE (id, name, is_asset, requires_serial) VALUES (1, 'Notebook', 1, 1)");
            s.executeUpdate("INSERT INTO TYPE (id, name, is_asset, requires_serial) VALUES (2, 'Mouse', 0, 0)");
            s.executeUpdate("INSERT INTO BRAND (id, name) VALUES (1, 'Dell')");
            s.executeUpdate("INSERT INTO BRAND (id, name) VALUES (2, 'Generic')");
            s.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (1, 1, 1)");
            s.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (2, 2, 2)");
            s.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name) VALUES (1, 1, 'Latitude 5420')");
            s.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name) VALUES (2, 2, 'Estandar')");
            s.executeUpdate("INSERT INTO SN_VALIDATION (model_id, regex_pattern, is_active) VALUES (1, '^[A-Z0-9]{8}$', 1)");
            s.executeUpdate("INSERT INTO PROVIDER (id, name) VALUES (1, 'Proveedor SA')");
        }
    }

    @Test
    void migratesFullCatalogWithRemappedIds() throws SQLException {
        seedSourceCatalog();

        Map<Integer, Integer> typeIds = CatalogMigrationTool.migrateTypes(source, target);
        Map<Integer, Integer> brandIds = CatalogMigrationTool.migrateBrands(source, target);
        Map<Integer, Integer> linkIds = CatalogMigrationTool.migrateBrandTypeLinks(source, target, typeIds, brandIds);
        Map<Integer, Integer> modelIds = CatalogMigrationTool.migrateModels(source, target, linkIds);
        CatalogMigrationTool.migrateSnValidations(source, target, modelIds);
        CatalogMigrationTool.migrateProviders(source, target);

        // The target's TYPE table already had one row (the "Dummy" seed, id=1) before migration,
        // so the source's own id=1 ("Notebook") must land on a different target id — proving the
        // migration used the target's own newly-generated id, not blindly copied the source's.
        assertNotEquals(1, (int) typeIds.get(1));
        assertEquals(2, typeIds.size());
        assertEquals(2, brandIds.size());
        assertEquals(2, linkIds.size());
        assertEquals(2, modelIds.size());

        assertEquals("Notebook", nameOf(target, "TYPE", typeIds.get(1)));
        assertEquals(1, intOf(target, "TYPE", typeIds.get(1), "requires_serial"));
        assertEquals("Mouse", nameOf(target, "TYPE", typeIds.get(2)));

        // Referential integrity: the migrated BRAND_TYPE_LINK/MODEL rows must point at the
        // *remapped* parent ids, not the source's original ones.
        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT type_id, brand_id FROM BRAND_TYPE_LINK WHERE id = " + linkIds.get(1))) {
            assertTrue(rs.next());
            assertEquals(typeIds.get(1).intValue(), rs.getInt("type_id"));
            assertEquals(brandIds.get(1).intValue(), rs.getInt("brand_id"));
        }
        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT brand_type_id, name FROM MODEL WHERE id = " + modelIds.get(1))) {
            assertTrue(rs.next());
            assertEquals(linkIds.get(1).intValue(), rs.getInt("brand_type_id"));
            assertEquals("Latitude 5420", rs.getString("name"));
        }

        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM SN_VALIDATION WHERE model_id = " + modelIds.get(1))) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM PROVIDER WHERE name = 'Proveedor SA'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    // A NULL brand_type_id (the single global "Genérico / Otro" model) has no BRAND_TYPE_LINK
    // to remap at all — migrateModels() must match/insert it on the target via
    // "brand_type_id IS NULL", not skip it as if its (nonexistent) link failed to migrate.
    @Test
    void migratesGlobalGenericModelWithoutABrandTypeLink() throws SQLException {
        try (Statement s = source.createStatement()) {
            s.executeUpdate("INSERT INTO TYPE (id, name, is_asset) VALUES (1, 'Notebook', 1)");
            s.executeUpdate("INSERT INTO BRAND (id, name) VALUES (1, 'Dell')");
            s.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (1, 1, 1)");
            s.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name) VALUES (1, 1, 'Latitude 5420')");
            s.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name, deprecated) VALUES (2, NULL, 'Genérico / Otro', 0)");
        }

        Map<Integer, Integer> typeIds = CatalogMigrationTool.migrateTypes(source, target);
        Map<Integer, Integer> brandIds = CatalogMigrationTool.migrateBrands(source, target);
        Map<Integer, Integer> linkIds = CatalogMigrationTool.migrateBrandTypeLinks(source, target, typeIds, brandIds);
        Map<Integer, Integer> modelIds = CatalogMigrationTool.migrateModels(source, target, linkIds);

        assertEquals(2, modelIds.size(), "the global generic model must not be skipped");
        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT brand_type_id FROM MODEL WHERE id = " + modelIds.get(2))) {
            assertTrue(rs.next());
            rs.getInt("brand_type_id");
            assertTrue(rs.wasNull(), "the migrated row must keep brand_type_id NULL, not remap it to a link");
        }

        // Re-running must match the existing NULL-scoped row by name, not insert a duplicate.
        Map<Integer, Integer> modelIdsAgain = CatalogMigrationTool.migrateModels(source, target, linkIds);
        assertEquals(modelIds.get(2), modelIdsAgain.get(2));
        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM MODEL WHERE brand_type_id IS NULL")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void reRunningMigrationIsIdempotent() throws SQLException {
        seedSourceCatalog();

        Map<Integer, Integer> typeIds1 = CatalogMigrationTool.migrateTypes(source, target);
        Map<Integer, Integer> brandIds1 = CatalogMigrationTool.migrateBrands(source, target);
        CatalogMigrationTool.migrateBrandTypeLinks(source, target, typeIds1, brandIds1);

        // Re-run against the same source with no new local data — every row should upsert onto
        // the exact same target ids as the first run, not create duplicates.
        Map<Integer, Integer> typeIds2 = CatalogMigrationTool.migrateTypes(source, target);
        Map<Integer, Integer> brandIds2 = CatalogMigrationTool.migrateBrands(source, target);

        assertEquals(typeIds1, typeIds2);
        assertEquals(brandIds1, brandIds2);

        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM TYPE WHERE name = 'Notebook'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void skipsOrphanedLinkWhenParentWasNotMigrated() throws SQLException {
        try (Statement s = source.createStatement()) {
            s.executeUpdate("INSERT INTO TYPE (id, name) VALUES (1, 'Notebook')");
            s.executeUpdate("INSERT INTO BRAND (id, name) VALUES (1, 'Dell')");
            // References brand_id=99, which doesn't exist in BRAND at all — simulates a data
            // integrity gap rather than something the migration itself could cause.
            s.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (1, 1, 99)");
        }

        Map<Integer, Integer> typeIds = CatalogMigrationTool.migrateTypes(source, target);
        Map<Integer, Integer> brandIds = CatalogMigrationTool.migrateBrands(source, target);
        Map<Integer, Integer> linkIds = CatalogMigrationTool.migrateBrandTypeLinks(source, target, typeIds, brandIds);

        assertTrue(linkIds.isEmpty(), "The orphaned link should be skipped, not fail the whole migration");
    }

    @Test
    void migratesSedesWithRemappedIds() throws SQLException {
        try (Statement s = source.createStatement()) {
            s.executeUpdate("INSERT INTO SEDE (id, name, deprecated) VALUES (1, 'Campus Norte', 0)");
        }
        // Same "target already has an unrelated row consuming id=1" setup as setUp()'s TYPE
        // seed, so a migrated Sede landing on the same id as its source row would be a
        // coincidence, not proof the remapping actually happened.
        try (Statement s = target.createStatement()) {
            s.executeUpdate("INSERT INTO SEDE (name, deprecated) VALUES ('Dummy Sede', 0)");
        }

        Map<Integer, Integer> sedeIds = CatalogMigrationTool.migrateSedes(source, target);

        assertNotEquals(1, (int) sedeIds.get(1));
        assertEquals("Campus Norte", nameOf(target, "SEDE", sedeIds.get(1)));

        // Re-run: same source, no new data — must match the existing row by name, not duplicate.
        Map<Integer, Integer> sedeIdsAgain = CatalogMigrationTool.migrateSedes(source, target);
        assertEquals(sedeIds.get(1), sedeIdsAgain.get(1));
    }

    @Test
    void migratesModelStockWithRemappedIds() throws SQLException {
        seedSourceCatalog();
        try (Statement s = source.createStatement()) {
            s.executeUpdate("INSERT INTO SEDE (id, name, deprecated) VALUES (1, 'Campus Norte', 0)");
            s.executeUpdate("INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (1, 1, 1, 14)");
        }

        Map<Integer, Integer> typeIds = CatalogMigrationTool.migrateTypes(source, target);
        Map<Integer, Integer> brandIds = CatalogMigrationTool.migrateBrands(source, target);
        Map<Integer, Integer> linkIds = CatalogMigrationTool.migrateBrandTypeLinks(source, target, typeIds, brandIds);
        Map<Integer, Integer> modelIds = CatalogMigrationTool.migrateModels(source, target, linkIds);
        Map<Integer, Integer> sedeIds = CatalogMigrationTool.migrateSedes(source, target);
        CatalogMigrationTool.migrateModelStock(source, target, linkIds, modelIds, sedeIds);

        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT stock FROM MODEL_STOCK WHERE brand_type_id = " + linkIds.get(1)
                     + " AND model_id = " + modelIds.get(1)
                     + " AND sede_id = " + sedeIds.get(1))) {
            assertTrue(rs.next(), "stock row must land on the remapped (link, model, sede) id triple");
            assertEquals(14, rs.getInt("stock"));
        }

        // Re-run: same source, no new data — stock should update in place, not duplicate.
        CatalogMigrationTool.migrateModelStock(source, target, linkIds, modelIds, sedeIds);
        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM MODEL_STOCK")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void migrateModelStockSkipsRowsWhoseLinkOrModelWasNotMigrated() throws SQLException {
        seedSourceCatalog();
        try (Statement s = source.createStatement()) {
            s.executeUpdate("INSERT INTO SEDE (id, name, deprecated) VALUES (1, 'Campus Norte', 0)");
            // References model_id=99, which was never migrated — a data-integrity gap, same
            // convention as skipsOrphanedLinkWhenParentWasNotMigrated above.
            s.executeUpdate("INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (1, 99, 1, 5)");
        }

        Map<Integer, Integer> typeIds = CatalogMigrationTool.migrateTypes(source, target);
        Map<Integer, Integer> brandIds = CatalogMigrationTool.migrateBrands(source, target);
        Map<Integer, Integer> linkIds = CatalogMigrationTool.migrateBrandTypeLinks(source, target, typeIds, brandIds);
        Map<Integer, Integer> modelIds = CatalogMigrationTool.migrateModels(source, target, linkIds);
        Map<Integer, Integer> sedeIds = CatalogMigrationTool.migrateSedes(source, target);
        CatalogMigrationTool.migrateModelStock(source, target, linkIds, modelIds, sedeIds);

        try (Statement s = target.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM MODEL_STOCK")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "the orphaned stock row should be skipped, not fail the migration");
        }
    }

    private String nameOf(Connection c, String table, int id) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM " + table + " WHERE id = " + id)) {
            rs.next();
            return rs.getString("name");
        }
    }

    private int intOf(Connection c, String table, int id, String column) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + column + " FROM " + table + " WHERE id = " + id)) {
            rs.next();
            return rs.getInt(column);
        }
    }
}
