package com.bunshock.note_app_for_it.catalog;

import com.bunshock.note_app_for_it.catalog.dto.AddTypeRequest;
import com.bunshock.note_app_for_it.catalog.dto.CatalogBrand;
import com.bunshock.note_app_for_it.catalog.dto.CatalogModel;
import com.bunshock.note_app_for_it.catalog.dto.CatalogProvider;
import com.bunshock.note_app_for_it.catalog.dto.CatalogSede;
import com.bunshock.note_app_for_it.catalog.dto.CatalogType;
import com.bunshock.note_app_for_it.catalog.dto.NameRequest;
import com.bunshock.note_app_for_it.catalog.dto.RequiresSerialRequest;
import com.bunshock.note_app_for_it.catalog.dto.SedeShippingInfoResponse;
import com.bunshock.note_app_for_it.catalog.dto.SnValidationResponse;
import com.bunshock.note_app_for_it.catalog.dto.SnValidationRow;
import com.bunshock.note_app_for_it.catalog.dto.SnValidationUpdateRequest;
import com.bunshock.note_app_for_it.catalog.dto.StockResponse;
import com.bunshock.note_app_for_it.catalog.dto.StockUpdateRequest;
import com.bunshock.note_app_for_it.common.security.CallerPrincipal;
import com.bunshock.note_app_for_it.common.security.CurrentUser;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.rbac.Permission;
import com.bunshock.note_app_for_it.rbac.PermissionGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * backend-contract.md §3.1/§3.3/§3.4, v1 shape (see the approved plan's scope table): §3.1
 * browse is unchanged in spirit, but stock (§3.3/§3.4) is the OLD stored-counter model, not
 * derived "Model Y" — there's no external system for v1 to derive it from.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogRepository catalog;
    private final CurrentUser currentUser;
    private final PermissionGuard permissionGuard;

    public CatalogController(CatalogRepository catalog, CurrentUser currentUser, PermissionGuard permissionGuard) {
        this.catalog = catalog;
        this.currentUser = currentUser;
        this.permissionGuard = permissionGuard;
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

    @GetMapping("/models/{modelId}/stock")
    public StockResponse getStock(@PathVariable int modelId,
            @RequestParam int brandId, @RequestParam int typeId, @RequestParam int sedeId) {
        currentUser.require();
        return new StockResponse(catalog.getModelStock(modelId, brandId, typeId, sedeId));
    }

    @PutMapping("/models/{modelId}/stock")
    public StockResponse setStock(@PathVariable int modelId,
            @RequestParam int brandId, @RequestParam int typeId, @RequestParam int sedeId,
            @Valid @RequestBody StockUpdateRequest request) {
        CallerPrincipal caller = currentUser.require();
        // Stock is per-Sede, so a plain ADMIN is fenced to their own Sede here too — same H2
        // pattern used for note approval/sync/return, even though MANAGE_STOCK itself isn't
        // one of the three note-flow permissions.
        permissionGuard.requireSedeScoped(caller, Permission.MANAGE_STOCK, sedeId);
        catalog.setModelStock(modelId, brandId, typeId, sedeId, request.stock(), request.reason(), caller.username());
        return new StockResponse(request.stock());
    }

    // ── Type CRUD ────────────────────────────────────────────────────────────

    @PostMapping("/types")
    public ResponseEntity<Void> addType(@Valid @RequestBody AddTypeRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_TYPES);
        catalog.addType(request.name(), request.isAsset(), caller.username());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/types/{id}")
    public void renameType(@PathVariable int id, @Valid @RequestBody NameRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_TYPES);
        catalog.renameType(id, request.name(), caller.username());
    }

    @PutMapping("/types/{id}/requires-serial")
    public void setRequiresSerial(@PathVariable int id, @RequestBody RequiresSerialRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_TYPES);
        catalog.setRequiresSerial(id, request.requiresSerial(), caller.username());
    }

    @DeleteMapping("/types/{id}")
    public void removeType(@PathVariable int id) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_TYPES);
        catalog.removeType(id, caller.username());
    }

    // ── Brand CRUD ───────────────────────────────────────────────────────────

    @PostMapping("/brands")
    public ResponseEntity<Void> addBrand(@RequestParam(required = false) Integer typeId,
            @Valid @RequestBody NameRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_BRANDS);
        if (typeId != null) {
            catalog.addBrandForType(request.name(), typeId, caller.username());
        } else {
            catalog.addBrand(request.name(), caller.username());
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/brands/{id}")
    public void renameBrand(@PathVariable int id, @Valid @RequestBody NameRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_BRANDS);
        catalog.renameBrand(id, request.name(), caller.username());
    }

    @DeleteMapping("/brands/{id}")
    public void removeBrand(@PathVariable int id) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_BRANDS);
        catalog.removeBrand(id, caller.username());
    }

    // ── Model CRUD ───────────────────────────────────────────────────────────

    @PostMapping("/models")
    public ResponseEntity<Void> addModel(@RequestParam int typeId, @RequestParam int brandId,
            @Valid @RequestBody NameRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_MODELS);
        catalog.addModel(request.name(), brandId, typeId, caller.username());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/models/{id}")
    public void renameModel(@PathVariable int id, @Valid @RequestBody NameRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_MODELS);
        catalog.renameModel(id, request.name(), caller.username());
    }

    @DeleteMapping("/models/{id}")
    public void removeModel(@PathVariable int id) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.MANAGE_MODELS);
        catalog.removeModel(id, caller.username());
    }

    // ── S/N Validation (regex-per-model) ───────────────────────────────────

    @GetMapping("/sn-validations")
    public List<SnValidationRow> snValidations() {
        currentUser.require();
        return catalog.getAllSnValidationRows();
    }

    @GetMapping("/models/{modelId}/sn-validation")
    public SnValidationResponse getSnValidation(@PathVariable int modelId) {
        currentUser.require();
        SnValidationResponse rule = catalog.getSnValidation(modelId);
        if (rule == null) {
            throw ApiException.notFound("SN_VALIDATION_NOT_FOUND",
                    "Este modelo no tiene una regla de validación de S/N activa.");
        }
        return rule;
    }

    @PutMapping("/models/{modelId}/sn-validation")
    public void setSnValidation(@PathVariable int modelId,
            @Valid @RequestBody SnValidationUpdateRequest request) {
        CallerPrincipal caller = currentUser.require();
        permissionGuard.require(caller, Permission.EDIT_SN_VALIDATION);
        catalog.upsertSnValidation(modelId, request.regexPattern(), request.active(), caller.username());
    }

    // ── Provider / Sede — read-only from the app ────────────────────────────

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

    @GetMapping("/sedes/{id}/shipping-info")
    public SedeShippingInfoResponse sedeShippingInfo(@PathVariable int id) {
        currentUser.require();
        SedeShippingInfoResponse info = catalog.getSedeShippingInfo(id);
        if (info == null) {
            throw ApiException.notFound("SHIPPING_INFO_NOT_FOUND", "Esta Sede no tiene información de envío configurada.");
        }
        return info;
    }
}
