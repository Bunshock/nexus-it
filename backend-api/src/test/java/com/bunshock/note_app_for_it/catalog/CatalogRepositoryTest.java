package com.bunshock.note_app_for_it.catalog;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.catalog.dto.CatalogBrand;
import com.bunshock.note_app_for_it.catalog.dto.CatalogModel;
import com.bunshock.note_app_for_it.catalog.dto.CatalogProvider;
import com.bunshock.note_app_for_it.catalog.dto.CatalogSede;
import com.bunshock.note_app_for_it.catalog.dto.CatalogType;
import com.bunshock.note_app_for_it.catalog.dto.SedeShippingInfoResponse;
import com.bunshock.note_app_for_it.catalog.dto.SnValidationRow;
import com.bunshock.note_app_for_it.common.security.EncryptionService;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.config.ConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JDBC test against an embedded H2 (catalog-repository-test-schema.sql — an H2-native
 * stand-in, not db/migration/V1__init.sql, which is SQL-Server-only T-SQL — see that file's
 * header comment). Explicitly loaded via @Sql rather than the classpath-root schema.sql
 * convention — the latter would also auto-run against NoteAppForItApplicationTests' full
 * @SpringBootTest context, whose dev-profile datasource runs H2 in MSSQLServer compatibility
 * mode (a different SQL dialect than this file's plain H2 syntax). Each test runs in its own
 * rolled-back transaction (JdbcTest default).
 */
// @DirtiesContext forces a fresh embedded H2 instance for this class — @JdbcTest slice
// contexts are cached/shared by Spring across test classes with identical configuration,
// and this class's own @Sql schema (via IF NOT EXISTS) would otherwise silently lose a
// table-shape race against whichever other *RepositoryTest class happened to run first
// in the same JVM and already created a same-named table with different columns.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@JdbcTest
@Sql("/catalog-repository-test-schema.sql")
class CatalogRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private CatalogRepository repository;

    private int notebookTypeId;
    private int dellBrandId;
    private int sedeId;

    @BeforeEach
    void setUp() {
        // A fresh all-zero test-only key — never used outside this test, not a real secret.
        AuditRepository auditRepository = new AuditRepository(jdbc);
        ConfigRepository configRepository = new ConfigRepository(jdbc,
                new EncryptionService(Base64.getEncoder().encodeToString(new byte[32])), auditRepository);
        repository = new CatalogRepository(jdbc, configRepository, auditRepository);

        notebookTypeId = insertType("NOTEBOOK", true, true);
        dellBrandId = insertBrand("DELL");
        sedeId = insertSede("Campus Central");
    }

    @Test
    void getAllTypesReturnsOnlyActiveTypesOrderedByName() {
        insertType("MONITOR", true, false);
        jdbc.update("UPDATE TYPE SET deprecated = 1 WHERE name = 'MONITOR'");
        insertType("MOUSE", false, false);

        List<CatalogType> types = repository.getAllTypes();

        assertEquals(List.of("MOUSE", "NOTEBOOK"), types.stream().map(CatalogType::name).toList());
    }

    @Test
    void getBrandsForTypeOnlyReturnsBrandsActuallyLinkedToThatType() {
        insertBrand("HP"); // a real brand, but never linked to NOTEBOOK — must not appear
        link(notebookTypeId, dellBrandId);

        List<CatalogBrand> brands = repository.getBrandsForType(notebookTypeId);

        assertEquals(List.of("DELL"), brands.stream().map(CatalogBrand::name).toList());
    }

    @Test
    void getModelsForBrandAndTypeUnionsInTheGlobalGenericModelRegardlessOfLink() {
        int linkId = link(notebookTypeId, dellBrandId);
        insertModel(linkId, "Latitude 5420");
        insertGlobalGenericModel("Genérico / Otro");

        List<CatalogModel> models = repository.getModelsForBrandAndType(dellBrandId, notebookTypeId);

        assertEquals(List.of("Genérico / Otro", "Latitude 5420"),
                models.stream().map(CatalogModel::name).toList());
        assertNull(models.stream().filter(m -> m.name().equals("Genérico / Otro")).findFirst()
                .orElseThrow().brandTypeId());
    }

    @Test
    void modelsForACombinationWithNoRealLinkStillOffersTheGlobalGenericModel() {
        insertGlobalGenericModel("Genérico / Otro");
        int neverLinkedBrandId = insertBrand("ASUS");

        List<CatalogModel> models = repository.getModelsForBrandAndType(neverLinkedBrandId, notebookTypeId);

        assertEquals(List.of("Genérico / Otro"), models.stream().map(CatalogModel::name).toList());
    }

    @Test
    void stockDefaultsToZeroForAnUnknownCombination() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");
        assertEquals(0, repository.getModelStock(modelId, dellBrandId, notebookTypeId, sedeId));
    }

    @Test
    void setThenGetStockRoundTrips() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, 12, "Ajuste de prueba", "tester");

        assertEquals(12, repository.getModelStock(modelId, dellBrandId, notebookTypeId, sedeId));
    }

    @Test
    void settingStockWritesAnAuditStockRowOnlyWhenTheValueActuallyChanges() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, 12, "Reposición mensual", "tester");
        Integer countAfterRealChange = jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_STOCK", Integer.class);
        assertEquals(1, countAfterRealChange);
        var row = jdbc.queryForMap("SELECT * FROM AUDIT_STOCK");
        assertEquals(0, ((Number) row.get("old_stock")).intValue());
        assertEquals(12, ((Number) row.get("new_stock")).intValue());
        assertEquals("Reposición mensual", row.get("reason"));
        assertEquals("tester", row.get("username"));

        // Saving the SAME value again is a no-op — no second audit row, matching the desktop
        // app's own "a no-op save writes no audit row at all" rule.
        repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, 12, "Reposición mensual", "tester");
        Integer countAfterNoOp = jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_STOCK", Integer.class);
        assertEquals(1, countAfterNoOp);
    }

    @Test
    void settingStockTwiceUpdatesRatherThanDuplicating() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, 5, "Ajuste de prueba", "tester");
        repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, 8, "Ajuste de prueba", "tester");

        assertEquals(8, repository.getModelStock(modelId, dellBrandId, notebookTypeId, sedeId));
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM MODEL_STOCK WHERE model_id = ? AND sede_id = ?",
                Integer.class, modelId, sedeId);
        assertEquals(1, rowCount);
    }

    @Test
    void stockIsIndependentPerSede() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");
        int otherSedeId = insertSede("Campus Norte");

        repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, 10, "Ajuste de prueba", "tester");
        repository.setModelStock(modelId, dellBrandId, notebookTypeId, otherSedeId, 3, "Ajuste de prueba", "tester");

        assertEquals(10, repository.getModelStock(modelId, dellBrandId, notebookTypeId, sedeId));
        assertEquals(3, repository.getModelStock(modelId, dellBrandId, notebookTypeId, otherSedeId));
    }

    @Test
    void negativeStockIsRejected() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        ApiException ex = assertThrows(ApiException.class,
                () -> repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, -1, "Ajuste de prueba", "tester"));
        assertEquals("NEGATIVE_STOCK", ex.getCode());
    }

    @Test
    void ensureBrandTypeLinkCreatesTheLinkOnFirstStockWriteForANeverLinkedBrand() {
        int newBrandId = insertBrand("LENOVO");
        assertNull(repository.findBrandTypeLinkId(newBrandId, notebookTypeId));

        int modelId = insertGlobalGenericModel("Genérico / Otro");
        repository.setModelStock(modelId, newBrandId, notebookTypeId, sedeId, 4, "Ajuste de prueba", "tester");

        assertNotNull(repository.findBrandTypeLinkId(newBrandId, notebookTypeId));
        assertEquals(4, repository.getModelStock(modelId, newBrandId, notebookTypeId, sedeId));
    }

    // ── Type CRUD ────────────────────────────────────────────────────────────

    @Test
    void addTypeCreatesANewActiveType() {
        repository.addType("MONITOR", true, "tester");

        List<CatalogType> types = repository.getAllTypes();
        assertTrue(types.stream().anyMatch(t -> t.name().equals("MONITOR") && t.isAsset()));
    }

    @Test
    void addTypeRejectsDuplicateNameCaseInsensitive() {
        ApiException ex = assertThrows(ApiException.class, () -> repository.addType("notebook", false, "tester"));
        assertEquals("DUPLICATE_NAME", ex.getCode());
    }

    @Test
    void addTypeReactivatesADeprecatedRowInsteadOfInserting() {
        jdbc.update("UPDATE TYPE SET deprecated = 1 WHERE id = ?", notebookTypeId);

        repository.addType("NOTEBOOK", true, "tester");

        List<CatalogType> types = repository.getAllTypes();
        assertEquals(1, types.stream().filter(t -> t.name().equals("NOTEBOOK")).count());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM TYPE WHERE name = 'NOTEBOOK'", Integer.class);
        assertEquals(1, count, "must reuse the deprecated row, not insert a second one");
    }

    @Test
    void renameTypeToADifferentNameDeprecatesOldRowAndCreatesNewOne() {
        repository.renameType(notebookTypeId, "LAPTOP", "tester");

        List<CatalogType> types = repository.getAllTypes();
        assertEquals(List.of("LAPTOP"), types.stream().map(CatalogType::name).toList());
        Integer stillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM TYPE WHERE id = ? AND deprecated = 1", Integer.class, notebookTypeId);
        assertEquals(1, stillThere);
    }

    @Test
    void renameTypeWritesAnAuditAdminActionRow() {
        repository.renameType(notebookTypeId, "LAPTOP", "boss1");

        var row = jdbc.queryForMap("SELECT * FROM AUDIT_ADMIN_ACTION WHERE action = 'RENAME_TYPE'");
        assertEquals("boss1", row.get("username"));
        assertEquals("TYPE", row.get("target_type"));
        assertEquals(String.valueOf(notebookTypeId), row.get("target_id"));
        assertEquals("NOTEBOOK", row.get("old_value"));
        assertEquals("LAPTOP", row.get("new_value"));
    }

    @Test
    void removeTypeWritesAnAuditAdminActionRowWithTheRemovedName() {
        repository.removeType(notebookTypeId, "boss1");

        var row = jdbc.queryForMap("SELECT * FROM AUDIT_ADMIN_ACTION WHERE action = 'REMOVE_TYPE'");
        assertEquals("NOTEBOOK", row.get("old_value"));
        assertNull(row.get("new_value"));
    }

    @Test
    void renameTypeToItsOwnCurrentNameJustCasingIsANoOp() {
        repository.renameType(notebookTypeId, "Notebook", "tester");

        assertEquals(List.of("Notebook"), repository.getAllTypes().stream().map(CatalogType::name).toList());
    }

    @Test
    void renameTypeRejectsCollisionWithAnotherActiveType() {
        insertType("MONITOR", true, false);

        ApiException ex = assertThrows(ApiException.class, () -> repository.renameType(notebookTypeId, "MONITOR", "tester"));
        assertEquals("DUPLICATE_NAME", ex.getCode());
    }

    @Test
    void renameTypeClonesLinkedBrandsActiveModelsToTheNewTypeIdAndCarriesStock() {
        int linkId = link(notebookTypeId, dellBrandId);
        int modelId = insertModel(linkId, "Latitude 5420");
        repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, 7, "Ajuste de prueba", "tester");

        repository.renameType(notebookTypeId, "LAPTOP", "tester");

        int newTypeId = jdbc.queryForObject("SELECT id FROM TYPE WHERE name = 'LAPTOP'", Integer.class);
        List<CatalogModel> models = repository.getModelsForBrandAndType(dellBrandId, newTypeId);
        assertTrue(models.stream().anyMatch(m -> m.name().equals("Latitude 5420")));
        int newModelId = jdbc.queryForObject(
                "SELECT m.id FROM MODEL m JOIN BRAND_TYPE_LINK l ON l.id = m.brand_type_id " +
                "WHERE l.type_id = ? AND l.brand_id = ? AND m.name = 'Latitude 5420'",
                Integer.class, newTypeId, dellBrandId);
        assertEquals(7, repository.getModelStock(newModelId, dellBrandId, newTypeId, sedeId),
                "stock must carry forward through the rename cascade");
    }

    @Test
    void setRequiresSerialUpdatesTheFlag() {
        repository.setRequiresSerial(notebookTypeId, false, "tester");
        assertFalse(repository.getAllTypes().stream()
                .filter(t -> t.id() == notebookTypeId).findFirst().orElseThrow().requiresSerial());
    }

    @Test
    void removeTypeDeprecatesRatherThanDeletes() {
        repository.removeType(notebookTypeId, "tester");

        assertTrue(repository.getAllTypes().stream().noneMatch(t -> t.id() == notebookTypeId));
        Integer stillThere = jdbc.queryForObject("SELECT COUNT(*) FROM TYPE WHERE id = ?", Integer.class, notebookTypeId);
        assertEquals(1, stillThere);
    }

    // ── Brand CRUD ───────────────────────────────────────────────────────────

    @Test
    void addBrandForTypeLinksItToThatType() {
        int hpBrandId = insertBrand("HP");
        repository.addBrandForType("HP", notebookTypeId, "tester");

        assertEquals(List.of("HP"), repository.getBrandsForType(notebookTypeId).stream().map(CatalogBrand::name).toList());
        assertNotNull(repository.findBrandTypeLinkId(hpBrandId, notebookTypeId));
    }

    @Test
    void addBrandForTypeNeverLinksTheGenericBrand() {
        repository.addBrandForType("Genérico / Otro", notebookTypeId, "tester");

        int genericBrandId = jdbc.queryForObject("SELECT id FROM BRAND WHERE name = 'Genérico / Otro'", Integer.class);
        assertNull(repository.findBrandTypeLinkId(genericBrandId, notebookTypeId));
    }

    @Test
    void renameBrandRejectsCollisionWithAnotherActiveBrand() {
        insertBrand("HP");

        ApiException ex = assertThrows(ApiException.class, () -> repository.renameBrand(dellBrandId, "HP", "tester"));
        assertEquals("DUPLICATE_NAME", ex.getCode());
    }

    @Test
    void renameBrandClonesActiveModelsAcrossEveryTypeItWasLinkedTo() {
        int monitorTypeId = insertType("MONITOR", true, false);
        int dellNotebookLink = link(notebookTypeId, dellBrandId);
        int dellMonitorLink = link(monitorTypeId, dellBrandId);
        insertModel(dellNotebookLink, "Latitude 5420");
        insertModel(dellMonitorLink, "P2419H");

        repository.renameBrand(dellBrandId, "DELL TECHNOLOGIES", "tester");

        int newBrandId = jdbc.queryForObject("SELECT id FROM BRAND WHERE name = 'DELL TECHNOLOGIES'", Integer.class);
        assertTrue(repository.getModelsForBrandAndType(newBrandId, notebookTypeId).stream()
                .anyMatch(m -> m.name().equals("Latitude 5420")));
        assertTrue(repository.getModelsForBrandAndType(newBrandId, monitorTypeId).stream()
                .anyMatch(m -> m.name().equals("P2419H")));
    }

    @Test
    void removeBrandRefusesToDeleteTheGenericBrand() {
        repository.addBrand("Genérico / Otro", "tester");
        int genericBrandId = jdbc.queryForObject("SELECT id FROM BRAND WHERE name = 'Genérico / Otro'", Integer.class);

        ApiException ex = assertThrows(ApiException.class, () -> repository.removeBrand(genericBrandId, "tester"));
        assertEquals("CANNOT_REMOVE_GENERIC", ex.getCode());
    }

    // ── Model CRUD ───────────────────────────────────────────────────────────

    @Test
    void addModelRejectsDuplicateWithinSameBrandAndType() {
        int linkId = link(notebookTypeId, dellBrandId);
        insertModel(linkId, "Latitude 5420");

        ApiException ex = assertThrows(ApiException.class,
                () -> repository.addModel("Latitude 5420", dellBrandId, notebookTypeId, "tester"));
        assertEquals("DUPLICATE_NAME", ex.getCode());
    }

    @Test
    void addModelSameNameDifferentBrandTypeScopeIsAllowed() {
        int monitorTypeId = insertType("MONITOR", true, false);
        int dellNotebookLink = link(notebookTypeId, dellBrandId);
        int dellMonitorLink = link(monitorTypeId, dellBrandId);
        insertModel(dellNotebookLink, "Pro Series");

        assertDoesNotThrow(() -> repository.addModel("Pro Series", dellBrandId, monitorTypeId, "tester"));
    }

    @Test
    void renameModelOnTheGlobalGenericModelStaysGlobalAndDeprecatesOldRow() {
        int genericModelId = insertGlobalGenericModel("Genérico / Otro");

        repository.renameModel(genericModelId, "Genérico", "tester");

        List<CatalogModel> models = repository.getModelsForBrandAndType(dellBrandId, notebookTypeId);
        assertEquals(List.of("Genérico"), models.stream().map(CatalogModel::name).toList());
        assertNull(models.get(0).brandTypeId());
        Integer stillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM MODEL WHERE id = ? AND deprecated = 1", Integer.class, genericModelId);
        assertEquals(1, stillThere);
    }

    @Test
    void renameModelCarriesStockForwardToTheNewRow() {
        int linkId = link(notebookTypeId, dellBrandId);
        int modelId = insertModel(linkId, "Latitude 5420");
        repository.setModelStock(modelId, dellBrandId, notebookTypeId, sedeId, 9, "Ajuste de prueba", "tester");

        repository.renameModel(modelId, "Latitude 5430", "tester");

        int newModelId = jdbc.queryForObject(
                "SELECT id FROM MODEL WHERE brand_type_id = ? AND name = 'Latitude 5430'", Integer.class, linkId);
        assertEquals(9, repository.getModelStock(newModelId, dellBrandId, notebookTypeId, sedeId));
    }

    @Test
    void removeModelRefusesToDeleteTheGlobalGenericModel() {
        int genericModelId = insertGlobalGenericModel("Genérico / Otro");

        ApiException ex = assertThrows(ApiException.class, () -> repository.removeModel(genericModelId, "tester"));
        assertEquals("CANNOT_REMOVE_GLOBAL_GENERIC", ex.getCode());
    }

    @Test
    void removeModelDeprecatesAScopedModelNormally() {
        int linkId = link(notebookTypeId, dellBrandId);
        int modelId = insertModel(linkId, "Latitude 5420");

        repository.removeModel(modelId, "tester");

        assertTrue(repository.getModelsForBrandAndType(dellBrandId, notebookTypeId).isEmpty());
    }

    // ── S/N Validation ───────────────────────────────────────────────────────

    @Test
    void upsertThenGetSnValidationRoundTrips() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        repository.upsertSnValidation(modelId, "[A-Z]{2}\\d{6}", true, "tester");

        var rule = repository.getSnValidation(modelId);
        assertNotNull(rule);
        assertEquals("[A-Z]{2}\\d{6}", rule.regexPattern());
        assertTrue(rule.active());
    }

    @Test
    void upsertSnValidationReplacesTheExistingRowRatherThanDuplicating() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        repository.upsertSnValidation(modelId, "[A-Z]{2}\\d{6}", true, "tester");
        repository.upsertSnValidation(modelId, "\\d{8}", true, "tester");

        assertEquals("\\d{8}", repository.getSnValidation(modelId).regexPattern());
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM SN_VALIDATION WHERE model_id = ?", Integer.class, modelId);
        assertEquals(1, rowCount);
    }

    @Test
    void getSnValidationReturnsNullWhenTheRuleIsInactive() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");
        repository.upsertSnValidation(modelId, "\\d{8}", false, "tester");

        assertNull(repository.getSnValidation(modelId));
    }

    @Test
    void getSnValidationReturnsNullWhenNoRowExists() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");
        assertNull(repository.getSnValidation(modelId));
    }

    @Test
    void upsertSnValidationRejectsASyntacticallyInvalidRegexAndPersistsNothing() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        ApiException ex = assertThrows(ApiException.class,
                () -> repository.upsertSnValidation(modelId, "[A-Z", true, "tester"));
        assertEquals("INVALID_REGEX", ex.getCode());
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM SN_VALIDATION WHERE model_id = ?", Integer.class, modelId);
        assertEquals(0, rowCount, "a rejected pattern must not be persisted");
    }

    @Test
    void upsertSnValidationAcceptsABlankRegexAndStoresNoPattern() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        repository.upsertSnValidation(modelId, "   ", true, "tester");

        var rule = repository.getSnValidation(modelId);
        assertNotNull(rule);
        assertNull(rule.regexPattern());
    }

    @Test
    void upsertSnValidationRejectsAnUnknownModel() {
        ApiException ex = assertThrows(ApiException.class,
                () -> repository.upsertSnValidation(999_999, "\\d{8}", true, "tester"));
        assertEquals("MODEL_NOT_FOUND", ex.getCode());
    }

    @Test
    void upsertSnValidationWritesAnAuditAdminActionRowOnRealChangeOnly() {
        int modelId = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        repository.upsertSnValidation(modelId, "\\d{8}", true, "boss1");
        Integer afterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_ADMIN_ACTION WHERE action = 'EDIT_SN_VALIDATION'", Integer.class);
        assertEquals(1, afterFirst);
        var row = jdbc.queryForMap("SELECT * FROM AUDIT_ADMIN_ACTION WHERE action = 'EDIT_SN_VALIDATION'");
        assertEquals("SN_VALIDATION", row.get("target_type"));
        assertEquals(String.valueOf(modelId), row.get("target_id"));
        assertEquals("regex=, activa=false", row.get("old_value"));
        assertEquals("regex=\\d{8}, activa=true", row.get("new_value"));

        repository.upsertSnValidation(modelId, "\\d{8}", true, "boss1");
        Integer afterNoOp = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_ADMIN_ACTION WHERE action = 'EDIT_SN_VALIDATION'", Integer.class);
        assertEquals(1, afterNoOp, "re-saving identical values writes no second audit row");
    }

    @Test
    void getAllSnValidationRowsListsEveryActiveAssetModelLeftJoinedToItsRule() {
        int linkId = link(notebookTypeId, dellBrandId);
        int withRule = insertModel(linkId, "Latitude 5420");
        int withoutRule = insertModel(linkId, "Latitude 7430");
        repository.upsertSnValidation(withRule, "[A-Z]{2}\\d{6}", true, "tester");

        List<SnValidationRow> rows = repository.getAllSnValidationRows();

        assertEquals(2, rows.size());
        var ruled = rows.stream().filter(r -> r.modelId() == withRule).findFirst().orElseThrow();
        assertEquals("[A-Z]{2}\\d{6}", ruled.regexPattern());
        assertTrue(ruled.active());
        var unruled = rows.stream().filter(r -> r.modelId() == withoutRule).findFirst().orElseThrow();
        assertNull(unruled.regexPattern());
        assertFalse(unruled.active());
        assertEquals(withRule, rows.get(0).modelId(), "an active rule sorts ahead of models with none");
    }

    @Test
    void getAllSnValidationRowsExcludesCountableTypeModels() {
        int mouseTypeId = insertType("MOUSE", false, false);
        insertModel(link(mouseTypeId, dellBrandId), "MS-116");
        insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");

        List<SnValidationRow> rows = repository.getAllSnValidationRows();

        assertEquals(List.of("Latitude 5420"), rows.stream().map(SnValidationRow::modelName).toList());
    }

    // ── Provider / Sede reads ────────────────────────────────────────────────

    @Test
    void getAllProvidersReturnsOnlyActiveOnesOrderedByName() {
        insertProvider("Zeta Corp");
        insertProvider("Acme Corp");
        jdbc.update("INSERT INTO PROVIDER (name, deprecated) VALUES ('Old Corp', 1)");

        List<CatalogProvider> providers = repository.getAllProviders();

        assertEquals(List.of("Acme Corp", "Zeta Corp"), providers.stream().map(CatalogProvider::name).toList());
    }

    @Test
    void getAllSedesReturnsOnlyActiveOnesOrderedByName() {
        insertSede("Campus Norte");
        List<CatalogSede> sedes = repository.getAllSedes();
        assertEquals(List.of("Campus Central", "Campus Norte"), sedes.stream().map(CatalogSede::name).toList());
    }

    @Test
    void getSedeShippingInfoReturnsNullWhenNoneConfigured() {
        assertNull(repository.getSedeShippingInfo(sedeId));
    }

    @Test
    void getSedeShippingInfoReturnsTheActiveRow() {
        jdbc.update("INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, address, recipients) " +
                "VALUES (?, 'Depósito Central', 'Av. Siempre Viva 123', 'Juan Perez')", sedeId);

        SedeShippingInfoResponse info = repository.getSedeShippingInfo(sedeId);

        assertNotNull(info);
        assertEquals("Depósito Central", info.destinationLabel());
        assertEquals("Av. Siempre Viva 123", info.address());
    }

    @Test
    void getSedeIdsWithShippingInfoReturnsOnlyActiveRows() {
        int otherSede = insertSede("Campus Norte");
        jdbc.update("INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label) VALUES (?, 'Activo')", sedeId);
        jdbc.update("INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, deprecated) VALUES (?, 'Viejo', 1)", otherSede);

        assertEquals(java.util.Set.of(sedeId), repository.getSedeIdsWithShippingInfo());
    }

    // ── all-brands + stock rollups ─────────────────────────────────────────

    @Test
    void getAllBrandsReturnsEveryActiveBrandRegardlessOfType() {
        int hp = insertBrand("HP");
        insertBrand("Deprecated Co");
        jdbc.update("UPDATE BRAND SET deprecated = 1 WHERE name = 'Deprecated Co'");
        // DELL is only linked to NOTEBOOK; HP is linked to nothing — both must still come back.

        List<String> names = repository.getAllBrands().stream().map(CatalogBrand::name).toList();

        assertTrue(names.contains("DELL"));
        assertTrue(names.contains("HP"));
        assertFalse(names.contains("Deprecated Co"));
        assertEquals(hp, repository.getAllBrands().stream()
                .filter(b -> b.name().equals("HP")).findFirst().orElseThrow().id());
    }

    @Test
    void stockRollupsAggregateAndScopeBySede() {
        int monitorType = insertType("MONITOR", true, false);
        int hp = insertBrand("HP");
        int otherSede = insertSede("Campus Norte");

        int dellNb = insertModel(link(notebookTypeId, dellBrandId), "Latitude 5420");
        int hpNb   = insertModel(link(notebookTypeId, hp), "EliteBook");
        int dellMon = insertModel(link(monitorType, dellBrandId), "P2419H");

        repository.setModelStock(dellNb, dellBrandId, notebookTypeId, sedeId, 10, "seed", "tester");
        repository.setModelStock(hpNb, hp, notebookTypeId, sedeId, 4, "seed", "tester");
        repository.setModelStock(dellMon, dellBrandId, monitorType, sedeId, 7, "seed", "tester");
        repository.setModelStock(dellNb, dellBrandId, notebookTypeId, otherSede, 100, "seed", "tester");

        // by type, scoped to sedeId: NOTEBOOK = 10 + 4, MONITOR = 7
        assertEquals(14, repository.getStockTotalsByType(sedeId).get(notebookTypeId));
        assertEquals(7, repository.getStockTotalsByType(sedeId).get(monitorType));
        // by type, all Sedes combined: NOTEBOOK = 14 + 100
        assertEquals(114, repository.getStockTotalsByType(null).get(notebookTypeId));

        // by brand within NOTEBOOK, scoped: DELL = 10, HP = 4
        assertEquals(10, repository.getStockTotalsByBrandForType(notebookTypeId, sedeId).get(dellBrandId));
        assertEquals(4, repository.getStockTotalsByBrandForType(notebookTypeId, sedeId).get(hp));

        // by model within (NOTEBOOK, DELL), scoped: the Latitude = 10
        assertEquals(10, repository.getStockTotalsByModelForBrandAndType(dellBrandId, notebookTypeId, sedeId).get(dellNb));
    }

    @Test
    void stockRollupByModelIncludesTheGlobalGenericModel() {
        int link = link(notebookTypeId, dellBrandId);
        int scoped = insertModel(link, "Latitude 5420");
        int generic = insertGlobalGenericModel("Genérico / Otro");

        repository.setModelStock(scoped, dellBrandId, notebookTypeId, sedeId, 3, "seed", "tester");
        repository.setModelStock(generic, dellBrandId, notebookTypeId, sedeId, 5, "seed", "tester");

        Map<Integer, Integer> totals = repository.getStockTotalsByModelForBrandAndType(dellBrandId, notebookTypeId, sedeId);
        assertEquals(3, totals.get(scoped));
        assertEquals(5, totals.get(generic));
    }

    private int insertProvider(String name) {
        jdbc.update("INSERT INTO PROVIDER (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM PROVIDER WHERE name = ?", Integer.class, name);
    }

    private int insertType(String name, boolean isAsset, boolean requiresSerial) {
        jdbc.update("INSERT INTO TYPE (name, is_asset, requires_serial) VALUES (?, ?, ?)",
                name, isAsset ? 1 : 0, requiresSerial ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM TYPE WHERE name = ?", Integer.class, name);
    }

    private int insertBrand(String name) {
        jdbc.update("INSERT INTO BRAND (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM BRAND WHERE name = ?", Integer.class, name);
    }

    private int insertSede(String name) {
        jdbc.update("INSERT INTO SEDE (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM SEDE WHERE name = ?", Integer.class, name);
    }

    private int link(int typeId, int brandId) {
        jdbc.update("INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)", typeId, brandId);
        return jdbc.queryForObject(
                "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?", Integer.class, typeId, brandId);
    }

    private int insertModel(int brandTypeId, String name) {
        jdbc.update("INSERT INTO MODEL (brand_type_id, name) VALUES (?, ?)", brandTypeId, name);
        return jdbc.queryForObject(
                "SELECT id FROM MODEL WHERE brand_type_id = ? AND name = ?", Integer.class, brandTypeId, name);
    }

    private int insertGlobalGenericModel(String name) {
        jdbc.update("INSERT INTO MODEL (brand_type_id, name) VALUES (NULL, ?)", name);
        return jdbc.queryForObject(
                "SELECT id FROM MODEL WHERE brand_type_id IS NULL AND name = ?", Integer.class, name);
    }
}
