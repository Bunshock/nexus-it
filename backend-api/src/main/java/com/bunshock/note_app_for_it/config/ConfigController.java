package com.bunshock.note_app_for_it.config;

import com.bunshock.note_app_for_it.catalog.CatalogRepository;
import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.config.dto.AppConfigResponse;
import com.bunshock.note_app_for_it.config.dto.UpdateAppConfigRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §8 — single-org config (D1b).
 *
 * <p><b>M1 (GLPI-adapter strip):</b> {@code PUT} was per-field-group permission-gated
 * ({@code EDIT_AF_FORMAT_CONFIG}/{@code EDIT_SMTP_CONFIG}) — both permissions are gone along with
 * the rest of the trimmed 12→4 {@code Permission} enum, and SMTP itself was removed from
 * {@code APP_CONFIG} entirely. Config editing is now flat SUPERADMIN-gated, per the plan.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final ConfigRepository config;
    private final CatalogRepository catalog;
    private final CurrentUser currentUser;

    public ConfigController(ConfigRepository config, CatalogRepository catalog, CurrentUser currentUser) {
        this.config = config;
        this.catalog = catalog;
        this.currentUser = currentUser;
    }

    @GetMapping
    public AppConfigResponse get() {
        currentUser.require();
        Integer brandId = catalog.findGenericBrandId();
        Integer modelId = catalog.findGlobalGenericModelId();
        return config.getConfig(brandId == null ? null : brandId.toString(), modelId == null ? null : modelId.toString());
    }

    @PutMapping
    public AppConfigResponse update(@Valid @RequestBody UpdateAppConfigRequest request) {
        CallerPrincipal caller = currentUser.require();
        if (!caller.isSuperadmin()) {
            throw ApiException.forbidden("PERMISSION_DENIED", "No tiene permiso para modificar la configuración.");
        }
        config.updateConfig(request, caller.username());
        return get();
    }
}
