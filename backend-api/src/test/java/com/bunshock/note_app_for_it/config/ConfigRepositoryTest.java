package com.bunshock.note_app_for_it.config;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.common.security.EncryptionService;
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

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JDBC test against embedded H2 (config-repository-test-schema.sql — H2-native stand-in,
 * same precedent as CatalogRepositoryTest/NotesRepositoryTest).
 */
// @DirtiesContext forces a fresh embedded H2 instance for this class — @JdbcTest slice
// contexts are cached/shared by Spring across test classes with identical configuration,
// and this class's own @Sql schema (via IF NOT EXISTS) would otherwise silently lose a
// table-shape race against whichever other *RepositoryTest class happened to run first
// in the same JVM and already created a same-named table with different columns.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@JdbcTest
@Sql("/config-repository-test-schema.sql")
class ConfigRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ConfigRepository repository;

    @BeforeEach
    void setUp() {
        // A fresh all-zero test-only key — never used outside this test, not a real secret.
        repository = new ConfigRepository(jdbc,
                new EncryptionService(Base64.getEncoder().encodeToString(new byte[32])),
                new AuditRepository(jdbc));
    }

    private UpdateAppConfigRequest fullUpdateRequest() {
        return new UpdateAppConfigRequest(
                false, "AF", "_",
                "smtp.newhost.org", 465, "nuevo@example.org", "s3cr3t",
                25, "Rota", "Genérico",
                List.of("Garantía"),
                new MotivoOptions(List.of("Ingreso"), List.of("Fin"), List.of("Garantía"), List.of("Falla")),
                List.of("No enciende"));
    }

    @Test
    void getConfigReturnsSeededDefaultsAndNeverIncludesThePassword() {
        AppConfigResponse config = repository.getConfig(null, null);

        assertTrue(config.afEnabled());
        assertEquals("IT", config.afPrefix());
        assertEquals("smtp.example.org", config.smtpHost());
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
    void updateConfigWithGeneralPermissionUpdatesGeneralFieldsButNotSmtp() {
        repository.updateConfig(fullUpdateRequest(), true, false, "tester");

        AppConfigResponse config = repository.getConfig(null, null);
        assertFalse(config.afEnabled());
        assertEquals("AF", config.afPrefix());
        assertEquals(25, config.noteItemLimit());
        assertEquals("Rota", config.failureTriggerMotivo());
        assertEquals("Genérico", config.genericLabel());
        assertEquals(List.of("Garantía"), config.returnableMotivosProveedor());
        assertEquals(List.of("Ingreso"), config.motivoOptions().entrega());
        assertEquals(List.of("No enciende"), config.fallaOptions());
        // SMTP untouched despite being present in the request body.
        assertEquals("smtp.example.org", config.smtpHost());
    }

    @Test
    void updateConfigWithSmtpPermissionUpdatesSmtpButNotGeneralFields() {
        repository.updateConfig(fullUpdateRequest(), false, true, "tester");

        AppConfigResponse config = repository.getConfig(null, null);
        assertEquals("smtp.newhost.org", config.smtpHost());
        assertEquals(465, config.smtpPort());
        assertEquals("nuevo@example.org", config.smtpSenderAddress());
        // General fields untouched despite being present in the request body.
        assertTrue(config.afEnabled());
        assertEquals("IT", config.afPrefix());
        assertEquals(List.of("Garantía", "Reparación"), config.returnableMotivosProveedor());
    }

    @Test
    void updateConfigEncryptsThePasswordAndItIsNeverExposedByGetConfig() {
        repository.updateConfig(fullUpdateRequest(), false, true, "tester");

        Map<String, Object> row = jdbc.queryForMap("SELECT smtp_password_encrypted FROM APP_CONFIG WHERE id = 1");
        String stored = (String) row.get("smtp_password_encrypted");
        assertNotNull(stored);
        assertNotEquals("s3cr3t", stored, "must be encrypted, not stored in plaintext");

        AppConfigResponse config = repository.getConfig(null, null);
        // AppConfigResponse has no smtpPassword field at all — nothing to assert null on, this
        // just confirms the object still round-trips correctly after the write.
        assertEquals("smtp.newhost.org", config.smtpHost());
    }

    @Test
    void updateConfigWithBlankPasswordLeavesTheStoredPasswordUnchanged() {
        repository.updateConfig(fullUpdateRequest(), false, true, "tester"); // sets a real password first
        String afterFirstSet = (String) jdbc.queryForMap(
                "SELECT smtp_password_encrypted FROM APP_CONFIG WHERE id = 1").get("smtp_password_encrypted");

        UpdateAppConfigRequest blankPasswordRequest = new UpdateAppConfigRequest(
                true, "IT", "-", "smtp.newhost.org", 465, "nuevo@example.org", "",
                50, "Falla", "Genérico / Otro", List.of(),
                new MotivoOptions(List.of(), List.of(), List.of(), List.of()), List.of());
        repository.updateConfig(blankPasswordRequest, false, true, "tester");

        String afterBlankUpdate = (String) jdbc.queryForMap(
                "SELECT smtp_password_encrypted FROM APP_CONFIG WHERE id = 1").get("smtp_password_encrypted");
        assertEquals(afterFirstSet, afterBlankUpdate);
    }

    @Test
    void updateConfigWithNeitherPermissionChangesNothing() {
        repository.updateConfig(fullUpdateRequest(), false, false, "tester");

        AppConfigResponse config = repository.getConfig(null, null);
        assertTrue(config.afEnabled());
        assertEquals("IT", config.afPrefix());
        assertEquals("smtp.example.org", config.smtpHost());
    }

    @Test
    void updateConfigWritesAnEditGeneralConfigAuditRowOnlyWhenGranted() {
        repository.updateConfig(fullUpdateRequest(), true, false, "boss1");

        var row = jdbc.queryForMap("SELECT * FROM AUDIT_ADMIN_ACTION WHERE action = 'EDIT_GENERAL_CONFIG'");
        assertEquals("boss1", row.get("username"));
        Integer smtpRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_ADMIN_ACTION WHERE action = 'EDIT_SMTP_CONFIG'", Integer.class);
        assertEquals(0, smtpRows, "SMTP group wasn't granted this call, so no SMTP audit row should exist");
    }

    @Test
    void updateConfigWritesAnEditSmtpConfigAuditRowThatNeverContainsThePlaintextPassword() {
        repository.updateConfig(fullUpdateRequest(), false, true, "boss1");

        var row = jdbc.queryForMap("SELECT * FROM AUDIT_ADMIN_ACTION WHERE action = 'EDIT_SMTP_CONFIG'");
        assertEquals("boss1", row.get("username"));
        String oldValue = (String) row.get("old_value");
        String newValue = (String) row.get("new_value");
        assertFalse(newValue.contains("s3cr3t"), "the plaintext SMTP password must never appear in the audit row");
        assertTrue(newValue.contains("smtp.newhost.org"));
        assertNotNull(oldValue);
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
        repository.updateConfig(fullUpdateRequest(), true, false, "tester");

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
