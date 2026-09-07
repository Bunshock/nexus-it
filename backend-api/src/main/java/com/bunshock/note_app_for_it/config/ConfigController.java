package com.bunshock.note_app_for_it.config;

import com.bunshock.note_app_for_it.catalog.CatalogRepository;
import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.config.dto.AppConfigResponse;
import com.bunshock.note_app_for_it.config.dto.UpdateAppConfigRequest;
import com.bunshock.note_app_for_it.rbac.Permission;
import com.bunshock.note_app_for_it.rbac.RolePermissionRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * §8 — single-org config (D1b). See {@link ConfigRepository#updateConfig} for the per-field-group
 * permission gating this controller resolves before calling it.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final ConfigRepository config;
    private final CatalogRepository catalog;
    private final CurrentUser currentUser;
    private final RolePermissionRepository rolePermissions;

    public ConfigController(ConfigRepository config, CatalogRepository catalog, CurrentUser currentUser,
            RolePermissionRepository rolePermissions) {
        this.config = config;
        this.catalog = catalog;
        this.currentUser = currentUser;
        this.rolePermissions = rolePermissions;
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
        var permissions = rolePermissions.getPermissionsForRole(caller.role());
        boolean canEditGeneral = permissions.contains(Permission.EDIT_AF_FORMAT_CONFIG);
        boolean canEditSmtp = permissions.contains(Permission.EDIT_SMTP_CONFIG);
        if (!canEditGeneral && !canEditSmtp) {
            // Neither group grantable — nothing this caller is allowed to change; fail loud
            // rather than silently accepting a no-op PUT.
            throw ApiException.forbidden("PERMISSION_DENIED", "No tiene permiso para modificar la configuración.");
        }
        config.updateConfig(request, canEditGeneral, canEditSmtp, caller.username());
        return get();
    }
}
