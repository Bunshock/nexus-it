package com.bunshock.note_app_for_it.catalog;

import com.bunshock.note_app_for_it.catalog.dto.CatalogBrand;
import com.bunshock.note_app_for_it.catalog.dto.CatalogModel;
import com.bunshock.note_app_for_it.catalog.dto.CatalogProvider;
import com.bunshock.note_app_for_it.catalog.dto.CatalogSede;
import com.bunshock.note_app_for_it.catalog.dto.CatalogType;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only from M1 on (GLPI-adapter strip) — local catalog CRUD, per-Sede stock, and S/N
 * validation are all gone; GLPI is now the catalog's source of truth. Still backed by
 * {@link CatalogRepository}'s local-table reads for now — M3 rewires these onto the (currently
 * stub) {@code CatalogPort}/{@code GlpiCatalogAdapter} instead. Provider/Sede stay read-only, same
 * as before the strip (they never had CRUD endpoints here).
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogRepository catalog;
    private final CurrentUser currentUser;

    public CatalogController(CatalogRepository catalog, CurrentUser currentUser) {
        this.catalog = catalog;
        this.currentUser = currentUser;
    }

    @GetMapping("/types")
    public List<CatalogType> types() {
        currentUser.require();
        return catalog.getAllTypes();
    }

    @GetMapping("/brands")
    public List<CatalogBrand> brands(@RequestParam int typeId) {
        currentUser.require();
        return catalog.getBrandsForType(typeId);
    }

    @GetMapping("/models")
    public List<CatalogModel> models(@RequestParam int typeId, @RequestParam int brandId) {
        currentUser.require();
        return catalog.getModelsForBrandAndType(brandId, typeId);
    }

    @GetMapping("/providers")
    public List<CatalogProvider> providers() {
        currentUser.require();
        return catalog.getAllProviders();
    }

    @GetMapping("/sedes")
    public List<CatalogSede> sedes() {
        currentUser.require();
        return catalog.getAllSedes();
    }
}
