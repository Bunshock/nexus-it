package com.bunshock.note_app_for_it.config;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.common.security.EncryptionService;
import com.bunshock.note_app_for_it.config.dto.AppConfigResponse;
import com.bunshock.note_app_for_it.config.dto.MotivoOptions;
import com.bunshock.note_app_for_it.config.dto.UpdateAppConfigRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * §8 config storage — a genuinely new table for the middleware (see
 * {@code db/migration/V2__config.sql}'s header comment for why this didn't exist in the copied
 * {@code V1__init.sql}: the old dual-store desktop app kept this in a local file + local-only
 * SQLite, never synced to the shared remote DB; the middleware IS that shared store now).
 *
 * <p>{@link #getGenericLabel()} and {@link #getReturnableProviderMotivos()} back what used to be
 * hardcoded constants in {@code CatalogRepository}/{@code NotesRepository} — both now read live
 * from here instead.
 */
@Repository
public class ConfigRepository {

    private final JdbcTemplate jdbc;
    private final EncryptionService encryption;
    private final AuditRepository audit;

    public ConfigRepository(JdbcTemplate jdbc, EncryptionService encryption, AuditRepository audit) {
        this.jdbc = jdbc;
        this.encryption = encryption;
        this.audit = audit;
    }

    public String getGenericLabel() {
        return jdbc.queryForObject("SELECT generic_label FROM APP_CONFIG WHERE id = 1", String.class);
    }

    public List<String> getReturnableProviderMotivos() {
        return jdbc.query("SELECT motivo FROM APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO ORDER BY id",
                (rs, rowNum) -> rs.getString(1));
    }

    /** Never includes the SMTP password — see {@link AppConfigResponse}'s Javadoc. */
    public AppConfigResponse getConfig(String genericBrandId, String genericModelId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM APP_CONFIG WHERE id = 1");
        return new AppConfigResponse(
                ((Number) row.get("af_enabled")).intValue() == 1,
                (String) row.get("af_prefix"),
                (String) row.get("af_separator"),
                (String) row.get("smtp_host"),
                row.get("smtp_port") == null ? null : ((Number) row.get("smtp_port")).intValue(),
                (String) row.get("smtp_sender_address"),
                ((Number) row.get("note_item_limit")).intValue(),
                (String) row.get("failure_trigger_motivo"),
                (String) row.get("generic_label"),
                genericBrandId,
                genericModelId,
                getReturnableProviderMotivos(),
                getMotivoOptions(),
                getFallaOptions());
    }

    private MotivoOptions getMotivoOptions() {
        return new MotivoOptions(
                motivosForCategory("entrega"),
                motivosForCategory("finDeContrato"),
                motivosForCategory("proveedor"),
                motivosForCategory("devolucion"));
    }

    private List<String> motivosForCategory(String category) {
        return jdbc.query(
                "SELECT motivo FROM APP_CONFIG_MOTIVO_OPTION WHERE category = ? ORDER BY sort_order, id",
                (rs, rowNum) -> rs.getString(1), category);
    }

    private List<String> getFallaOptions() {
        return jdbc.query("SELECT option_value FROM APP_CONFIG_FALLA_OPTION ORDER BY sort_order, id",
                (rs, rowNum) -> rs.getString(1));
    }

    /**
     * Per-field-group permission gating, same shape as the desktop app's own
     * {@code SettingsController.handleSave()}: a group the caller lacks permission for keeps its
     * CURRENT stored value rather than being overwritten or rejecting the whole request — so a
     * plain ADMIN submitting the full config document (because the app always sends the whole
     * document back) can't accidentally wipe SMTP settings they can't see/edit.
     * {@code canEditGeneral} covers af format + the business-config fields (motivo/falla options,
     * returnable motivos, note item limit, failure trigger motivo, generic label) — the old app
     * never had a dedicated permission for the latter group (they were file-only, never
     * in-app-editable at all), so this reuses {@code EDIT_AF_FORMAT_CONFIG} as the closest
     * existing analog rather than inventing a new enum value. Flagged in IMPLEMENTED_ENDPOINTS.md
     * as a deliberate interpretation, not a certainty — revisit if it doesn't match intent.
     */
    @Transactional
    public void updateConfig(UpdateAppConfigRequest request, boolean canEditGeneral, boolean canEditSmtp, String username) {
        Map<String, Object> current = jdbc.queryForMap("SELECT * FROM APP_CONFIG WHERE id = 1");

        boolean afEnabled = canEditGeneral ? request.afEnabled() : ((Number) current.get("af_enabled")).intValue() == 1;
        String afPrefix = canEditGeneral ? request.afPrefix() : (String) current.get("af_prefix");
        String afSeparator = canEditGeneral ? request.afSeparator() : (String) current.get("af_separator");
        int noteItemLimit = canEditGeneral ? request.noteItemLimit() : ((Number) current.get("note_item_limit")).intValue();
        String failureTriggerMotivo = canEditGeneral ? request.failureTriggerMotivo() : (String) current.get("failure_trigger_motivo");
        String genericLabel = canEditGeneral ? request.genericLabel() : (String) current.get("generic_label");

        String smtpHost = canEditSmtp ? request.smtpHost() : (String) current.get("smtp_host");
        Object currentSmtpPort = current.get("smtp_port");
        Integer smtpPort = canEditSmtp ? request.smtpPort() : (currentSmtpPort == null ? null : ((Number) currentSmtpPort).intValue());
        String smtpSenderAddress = canEditSmtp ? request.smtpSenderAddress() : (String) current.get("smtp_sender_address");

        jdbc.update("""
                UPDATE APP_CONFIG SET af_enabled = ?, af_prefix = ?, af_separator = ?,
                    smtp_host = ?, smtp_port = ?, smtp_sender_address = ?,
                    note_item_limit = ?, failure_trigger_motivo = ?, generic_label = ?
                WHERE id = 1
                """,
                afEnabled ? 1 : 0, afPrefix, afSeparator,
                smtpHost, smtpPort, smtpSenderAddress,
                noteItemLimit, failureTriggerMotivo, genericLabel);

        boolean passwordChanged = canEditSmtp && request.smtpPassword() != null && !request.smtpPassword().isBlank();
        if (passwordChanged) {
            jdbc.update("UPDATE APP_CONFIG SET smtp_password_encrypted = ? WHERE id = 1",
                    encryption.encrypt(request.smtpPassword()));
        }

        if (canEditGeneral) {
            replaceList("APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO", "motivo", request.returnableMotivosProveedor());
            replaceMotivoCategory("entrega", request.motivoOptions().entrega());
            replaceMotivoCategory("finDeContrato", request.motivoOptions().finDeContrato());
            replaceMotivoCategory("proveedor", request.motivoOptions().proveedor());
            replaceMotivoCategory("devolucion", request.motivoOptions().devolucion());
            replaceList("APP_CONFIG_FALLA_OPTION", "option_value", request.fallaOptions());

            String oldGeneral = "af=" + current.get("af_enabled") + "/" + current.get("af_prefix") + "/"
                    + current.get("af_separator") + " limit=" + current.get("note_item_limit")
                    + " failMotivo=" + current.get("failure_trigger_motivo") + " genericLabel=" + current.get("generic_label");
            String newGeneral = "af=" + (afEnabled ? 1 : 0) + "/" + afPrefix + "/" + afSeparator
                    + " limit=" + noteItemLimit + " failMotivo=" + failureTriggerMotivo + " genericLabel=" + genericLabel;
            audit.recordAdminAction(username, "EDIT_GENERAL_CONFIG", "CONFIG", "general", oldGeneral, newGeneral, null);
        }

        // Per CLAUDE.md's "secrets never written to old_value/new_value" rule (ported as-is): the
        // SMTP host/port/sender aren't secrets and are logged plainly, but smtp_password itself
        // NEVER appears here — only whether it changed, same as the desktop app's own convention.
        if (canEditSmtp) {
            String oldSmtp = "host=" + current.get("smtp_host") + ":" + current.get("smtp_port")
                    + " sender=" + current.get("smtp_sender_address");
            String newSmtp = "host=" + smtpHost + ":" + smtpPort + " sender=" + smtpSenderAddress
                    + (passwordChanged ? " (password changed)" : "");
            audit.recordAdminAction(username, "EDIT_SMTP_CONFIG", "CONFIG", "smtp", oldSmtp, newSmtp, null);
        }
    }

    private void replaceMotivoCategory(String category, List<String> motivos) {
        jdbc.update("DELETE FROM APP_CONFIG_MOTIVO_OPTION WHERE category = ?", category);
        for (int i = 0; i < motivos.size(); i++) {
            jdbc.update("INSERT INTO APP_CONFIG_MOTIVO_OPTION (category, motivo, sort_order) VALUES (?, ?, ?)",
                    category, motivos.get(i), i);
        }
    }

    // Plain delete-then-insert, not a diff/merge — these lists are small, admin-curated, and
    // replaced wholesale on every PUT (real REST PUT semantics), same as the desktop app's own
    // SN_VALIDATION delete-then-insert-per-model precedent for a similarly small config list.
    private void replaceList(String table, String column, List<String> values) {
        jdbc.update("DELETE FROM " + table);
        for (String value : values) {
            jdbc.update("INSERT INTO " + table + " (" + column + ") VALUES (?)", value);
        }
    }
}
