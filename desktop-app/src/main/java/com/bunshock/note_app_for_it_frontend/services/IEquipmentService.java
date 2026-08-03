package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.Sede;
import com.bunshock.note_app_for_it_frontend.models.SnValidation;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;

public interface IEquipmentService {

    List<EquipmentType> getAllTypes();

    List<EquipmentBrand> getBrandsForType(int typeId);

    List<EquipmentBrand> getAllBrands();

    List<EquipmentModel> getModelsForBrandAndType(int brandId, int typeId);

    List<EquipmentProvider> getAllProviders();

    List<Sede> getAllSedes();

    Optional<SnValidation> getSnValidation(int modelId);

    List<SnValidationRow> getAllSnValidationRows();

    void upsertSnValidation(int modelId, String regex, boolean active);

    void addType(String name, boolean isAsset);

    void addBrand(String name);

    void addBrandForType(String brandName, int typeId);

    void addModel(String name, int brandId, int typeId);

    void addProvider(String name);

    void addSede(String name);

    void removeType(int typeId);

    void removeBrand(int brandId);

    void removeModel(int modelId);

    void removeProvider(int providerId);

    void removeSede(int sedeId);

    void renameType(int typeId, String newName);

    void setRequiresSerial(int typeId, boolean requiresSerial);

    void renameBrand(int brandId, String newName);

    void renameModel(int modelId, String newName);

    void renameProvider(int providerId, String newName);

    void renameSede(int sedeId, String newName);

    // Stock (Base de Datos: Type/Brand/Model rollups). Keyed by (modelId, brandId, typeId) rather
    // than just modelId — the single global "Genérico / Otro" model carries an independent stock
    // number per (Type,Brand) it's used under, not one shared number across all of them. sedeId is
    // required (not nullable) here — reading or writing one concrete stock number always requires
    // one concrete Sede context; there's no such thing as "the" stock number independent of site.
    int getModelStock(int modelId, int brandId, int typeId, int sedeId);

    void setModelStock(int modelId, int brandId, int typeId, int sedeId, int stock);

    // Batched rollup reads — one query per list-refresh, not one per row. Map key is the
    // Type/Brand/Model id respectively; a missing key means 0 (no MODEL_STOCK rows for it).
    // sedeId is nullable here, unlike getModelStock/setModelStock above: null means "every Sede
    // combined" (summed), used for SUPERADMIN's default Base de Datos view; non-null scopes the
    // rollup to one specific Sede, same as a plain ADMIN's own assigned Sede.
    Map<Integer, Integer> getStockTotalsByType(Integer sedeId);

    Map<Integer, Integer> getStockTotalsByBrandForType(int typeId, Integer sedeId);

    Map<Integer, Integer> getStockTotalsByModelForBrandAndType(int brandId, int typeId, Integer sedeId);
}
