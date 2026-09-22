package com.bunshock.note_app_for_it.config;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.config.dto.AppConfigResponse;
import com.bunshock.note_app_for_it.config.dto.MotivoOptions;
import com.bunshock.note_app_for_it.config.dto.UpdateAppConfigRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * §8 config storage. SMTP columns/logic removed in M1 (GLPI-adapter strip) — email notifications
 * are out of scope for this middleware. {@code PUT} is now a single flat SUPERADMIN-gated write
 * (see ConfigController) instead of the old per-field-group (general vs. SMTP) split, so
 * {@link #updateConfig} no longer takes editability flags.
 *
 * <p>{@link #getGenericLabel()} and {@link #getReturnableProviderMotivos()} back what used to be
 * hardcoded constants in {@code CatalogRepository}/{@code NotesRepository} — both read live from
 * here.
 */
@Repository
public class ConfigRepository {

    private final JdbcTemplate jdbc;
    private final AuditRepository audit;

    public ConfigRepository(JdbcTemplate jdbc, AuditRepository audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public String getGenericLabel() {
        return jdbc.queryForObject("SELECT generic_label FROM APP_CONFIG WHERE id = 1", String.class);
    }

    public List<String> getReturnableProviderMotivos() {
        return jdbc.query("SELECT motivo FROM APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO ORDER BY id",
                (rs, rowNum) -> rs.getString(1));
    }

    public AppConfigResponse getConfig(String genericBrandId, String genericModelId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM APP_CONFIG WHERE id = 1");
        return new AppConfigResponse(
                ((Number) row.get("af_enabled")).intValue() == 1,
                (String) row.get("af_prefix"),
                (String) row.get("af_separator"),
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

    @Transactional
    public void updateConfig(UpdateAppConfigRequest request, String username) {
        Map<String, Object> current = jdbc.queryForMap("SELECT * FROM APP_CONFIG WHERE id = 1");

        jdbc.update("""
                UPDATE APP_CONFIG SET af_enabled = ?, af_prefix = ?, af_separator = ?,
                    note_item_limit = ?, failure_trigger_motivo = ?, generic_label = ?
                WHERE id = 1
                """,
                request.afEnabled() ? 1 : 0, request.afPrefix(), request.afSeparator(),
                request.noteItemLimit(), request.failureTriggerMotivo(), request.genericLabel());

        replaceList("APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO", "motivo", request.returnableMotivosProveedor());
        replaceMotivoCategory("entrega", request.motivoOptions().entrega());
        replaceMotivoCategory("finDeContrato", request.motivoOptions().finDeContrato());
        replaceMotivoCategory("proveedor", request.motivoOptions().proveedor());
        replaceMotivoCategory("devolucion", request.motivoOptions().devolucion());
        replaceList("APP_CONFIG_FALLA_OPTION", "option_value", request.fallaOptions());

        String oldGeneral = "af=" + current.get("af_enabled") + "/" + current.get("af_prefix") + "/"
                + current.get("af_separator") + " limit=" + current.get("note_item_limit")
                + " failMotivo=" + current.get("failure_trigger_motivo") + " genericLabel=" + current.get("generic_label");
        String newGeneral = "af=" + (request.afEnabled() ? 1 : 0) + "/" + request.afPrefix() + "/" + request.afSeparator()
                + " limit=" + request.noteItemLimit() + " failMotivo=" + request.failureTriggerMotivo()
                + " genericLabel=" + request.genericLabel();
        audit.recordAdminAction(username, "EDIT_GENERAL_CONFIG", "CONFIG", "general", oldGeneral, newGeneral, null);
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
