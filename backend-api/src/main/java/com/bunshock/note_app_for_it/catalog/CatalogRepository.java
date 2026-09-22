package com.bunshock.note_app_for_it.catalog;

import com.bunshock.note_app_for_it.catalog.dto.CatalogBrand;
import com.bunshock.note_app_for_it.catalog.dto.CatalogModel;
import com.bunshock.note_app_for_it.catalog.dto.CatalogProvider;
import com.bunshock.note_app_for_it.catalog.dto.CatalogSede;
import com.bunshock.note_app_for_it.catalog.dto.CatalogType;
import com.bunshock.note_app_for_it.config.ConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Read-only local-catalog mirror (M1, GLPI-adapter strip) — the full CRUD/stock/S/N-validation
 * surface this class used to have (ported from the desktop app's {@code SqliteEquipmentService})
 * was removed once GLPI became the intended catalog source of truth; see M3 in the plan for the
 * rewire onto {@code CatalogPort}. TYPE/BRAND/MODEL/BRAND_TYPE_LINK/PROVIDER/SEDE tables
 * themselves are left in place (not dropped) purely to keep these read methods — and the note
 * item flow, which still FK-references TYPE/BRAND/MODEL — working until M3/M4 land; see M1's
 * commit message for the full reasoning on why this diverges from a literal reading of the plan.
 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;
    private final ConfigRepository config;

    public CatalogRepository(JdbcTemplate jdbc, ConfigRepository config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    /**
     * Resolves live from {@code APP_CONFIG.generic_label} on every call, not cached — only used by
     * {@link #findGenericBrandId()} now that the catalog-rename flow that used to also read this is
     * gone.
     */
    private String genericLabel() {
        return config.getGenericLabel();
    }

    public List<CatalogType> getAllTypes() {
        return jdbc.query(
                "SELECT id, name, is_asset, requires_serial FROM TYPE WHERE deprecated = 0 ORDER BY name",
                (rs, rowNum) -> new CatalogType(rs.getInt("id"), rs.getString("name"),
                        rs.getInt("is_asset") == 1, rs.getInt("requires_serial") == 1));
    }

    public List<CatalogBrand> getBrandsForType(int typeId) {
        return jdbc.query("""
                SELECT b.id, b.name FROM BRAND b
                JOIN BRAND_TYPE_LINK btl ON btl.brand_id = b.id
                WHERE btl.type_id = ? AND b.deprecated = 0 ORDER BY b.name
                """,
                (rs, rowNum) -> new CatalogBrand(rs.getInt("id"), rs.getString("name")),
                typeId);
    }

    // The global "Genérico / Otro" model (brand_type_id IS NULL) is unioned in regardless of
    // the requested brand+type — see the desktop app's own comment on this same query, ported
    // verbatim: it's not scoped to any particular combination, so it's offered for every one of
    // them, whether or not that combination has ever had a real BRAND_TYPE_LINK.
    public List<CatalogModel> getModelsForBrandAndType(int brandId, int typeId) {
        return jdbc.query("""
                SELECT m.id, m.brand_type_id, m.name FROM MODEL m
                JOIN BRAND_TYPE_LINK btl ON btl.id = m.brand_type_id
                WHERE btl.type_id = ? AND btl.brand_id = ? AND m.deprecated = 0
                UNION
                SELECT m.id, m.brand_type_id, m.name FROM MODEL m
                WHERE m.brand_type_id IS NULL AND m.deprecated = 0
                ORDER BY name
                """,
                (rs, rowNum) -> new CatalogModel(rs.getInt("id"),
                        (Integer) rs.getObject("brand_type_id"), rs.getString("name")),
                typeId, brandId);
    }

    /** Resolves the actual, live "Genérico / Otro" Brand/Model catalog ids — see {@code ConfigController}. */
    public Integer findGenericBrandId() {
        List<Integer> ids = jdbc.query(
                "SELECT id FROM BRAND WHERE LOWER(name) = LOWER(?) AND deprecated = 0",
                (rs, rowNum) -> rs.getInt(1), genericLabel());
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Integer findGlobalGenericModelId() {
        List<Integer> ids = jdbc.query(
                "SELECT id FROM MODEL WHERE brand_type_id IS NULL AND deprecated = 0",
                (rs, rowNum) -> rs.getInt(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ── Provider / Sede — read-only from the app (no CRUD methods exist in the desktop app's own
    //    SqliteEquipmentService either — direct-SQL-only, same convention as APP_USER) ─────────

    public List<CatalogProvider> getAllProviders() {
        return jdbc.query("SELECT id, name FROM PROVIDER WHERE deprecated = 0 ORDER BY name",
                (rs, rowNum) -> new CatalogProvider(rs.getInt("id"), rs.getString("name")));
    }

    public List<CatalogSede> getAllSedes() {
        return jdbc.query("SELECT id, name FROM SEDE WHERE deprecated = 0 ORDER BY name",
                (rs, rowNum) -> new CatalogSede(rs.getInt("id"), rs.getString("name")));
    }
}
