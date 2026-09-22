package com.bunshock.note_app_for_it.catalog;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.catalog.dto.CatalogBrand;
import com.bunshock.note_app_for_it.catalog.dto.CatalogModel;
import com.bunshock.note_app_for_it.catalog.dto.CatalogProvider;
import com.bunshock.note_app_for_it.catalog.dto.CatalogSede;
import com.bunshock.note_app_for_it.catalog.dto.CatalogType;
import com.bunshock.note_app_for_it.config.ConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JDBC test against an embedded H2 (catalog-repository-test-schema.sql — an H2-native
 * stand-in, not db/migration/V1__init.sql, which is SQL-Server-only T-SQL — see that file's
 * header comment). Each test runs in its own rolled-back transaction (JdbcTest default).
 *
 * <p>M1 (GLPI-adapter strip): {@code CatalogRepository} is read-only from this class on —
 * stock/S/N-validation/Type-Brand-Model CRUD tests were all removed along with the production
 * code they covered (see CatalogRepository's own class Javadoc). What's left covers the browse
 * queries CatalogController still serves, plus the "Genérico / Otro" id resolution
 * {@code ConfigController} needs.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@JdbcTest
@Sql("/catalog-repository-test-schema.sql")
class CatalogRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private CatalogRepository repository;

    private int notebookTypeId;
    private int dellBrandId;

    @BeforeEach
    void setUp() {
        AuditRepository auditRepository = new AuditRepository(jdbc);
        ConfigRepository configRepository = new ConfigRepository(jdbc, auditRepository);
        repository = new CatalogRepository(jdbc, configRepository);

        notebookTypeId = insertType("NOTEBOOK", true, true);
        dellBrandId = insertBrand("DELL");
        insertSede("Campus Central");
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
    void findGenericBrandIdResolvesTheRowMatchingConfiguredGenericLabelCaseInsensitively() {
        int genericBrandId = insertBrand("Genérico / Otro");
        assertEquals(genericBrandId, repository.findGenericBrandId());
    }

    @Test
    void findGenericBrandIdReturnsNullWhenNoSuchActiveBrandExists() {
        assertNull(repository.findGenericBrandId());
    }

    @Test
    void findGlobalGenericModelIdResolvesTheOnlyBrandTypeIdNullRow() {
        int modelId = insertGlobalGenericModel("Genérico / Otro");
        assertEquals(modelId, repository.findGlobalGenericModelId());
    }

    @Test
    void findGlobalGenericModelIdReturnsNullWhenNoneExists() {
        assertNull(repository.findGlobalGenericModelId());
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
