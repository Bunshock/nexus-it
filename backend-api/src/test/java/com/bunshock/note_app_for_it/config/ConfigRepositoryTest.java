package com.bunshock.note_app_for_it.config;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.config.dto.AppConfigResponse;
import com.bunshock.note_app_for_it.config.dto.MotivoOptions;
import com.bunshock.note_app_for_it.config.dto.UpdateAppConfigRequest;
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
 * Real JDBC test against embedded H2 (config-repository-test-schema.sql — H2-native stand-in,
 * same precedent as CatalogRepositoryTest/NotesRepositoryTest).
 *
 * <p>M1 (GLPI-adapter strip): SMTP fields/columns are gone, and {@code updateConfig} is a single
 * unconditional write path now (the caller-side SUPERADMIN gate lives in ConfigController, not
 * here) — the old per-field-group (general vs. SMTP) permission split and its tests were removed.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@JdbcTest
@Sql("/config-repository-test-schema.sql")
class ConfigRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ConfigRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ConfigRepository(jdbc, new AuditRepository(jdbc));
    }

    private UpdateAppConfigRequest fullUpdateRequest() {
        return new UpdateAppConfigRequest(
                false, "AF", "_",
                25, "Rota", "Genérico",
                List.of("Garantía"),
                new MotivoOptions(List.of("Ingreso"), List.of("Fin"), List.of("Garantía"), List.of("Falla")),
                List.of("No enciende"));
    }

    @Test
    void getConfigReturnsSeededDefaults() {
        AppConfigResponse config = repository.getConfig(null, null);

        assertTrue(config.afEnabled());
        assertEquals("IT", config.afPrefix());
        assertEquals(List.of("Garantía", "Reparación"), config.returnableMotivosProveedor());
        assertEquals(List.of("Nuevo ingreso", "Otro"), config.motivoOptions().entrega());
        assertEquals(List.of("Falla"), config.motivoOptions().devolucion());
        assertEquals(List.of("No enciende", "Otro"), config.fallaOptions());
    }

    @Test
    void getConfigPassesThroughTheProvidedGenericIds() {
        AppConfigResponse config = repository.getConfig("42", "7");
        assertEquals("42", config.genericBrandId());
        assertEquals("7", config.genericModelId());
    }

    @Test
    void updateConfigUpdatesEveryField() {
        repository.updateConfig(fullUpdateRequest(), "tester");

        AppConfigResponse config = repository.getConfig(null, null);
        assertFalse(config.afEnabled());
        assertEquals("AF", config.afPrefix());
        assertEquals(25, config.noteItemLimit());
        assertEquals("Rota", config.failureTriggerMotivo());
        assertEquals("Genérico", config.genericLabel());
        assertEquals(List.of("Garantía"), config.returnableMotivosProveedor());
        assertEquals(List.of("Ingreso"), config.motivoOptions().entrega());
        assertEquals(List.of("No enciende"), config.fallaOptions());
    }

    @Test
    void updateConfigWritesAnEditGeneralConfigAuditRow() {
        repository.updateConfig(fullUpdateRequest(), "boss1");

        var row = jdbc.queryForMap("SELECT * FROM AUDIT_ADMIN_ACTION WHERE action = 'EDIT_GENERAL_CONFIG'");
        assertEquals("boss1", row.get("username"));
    }

    @Test
    void getGenericLabelReadsTheStoredValue() {
        assertEquals("Genérico / Otro", repository.getGenericLabel());
    }

    @Test
    void getReturnableProviderMotivosReadsTheSeededList() {
        assertEquals(List.of("Garantía", "Reparación"), repository.getReturnableProviderMotivos());
    }

    @Test
    void replacingMotivoOptionsIsWholesaleNotMerged() {
        repository.updateConfig(fullUpdateRequest(), "tester");

        // fullUpdateRequest's finDeContrato/proveedor lists ("Fin"/"Garantía") replace whatever
        // was there before (nothing, in the seed) — confirm the category-scoping doesn't leak
        // rows across categories.
        AppConfigResponse config = repository.getConfig(null, null);
        assertEquals(List.of("Fin"), config.motivoOptions().finDeContrato());
        assertEquals(List.of("Garantía"), config.motivoOptions().proveedor());
        assertEquals(List.of("Falla"), config.motivoOptions().devolucion());
        assertEquals(List.of("Ingreso"), config.motivoOptions().entrega());
    }
}
