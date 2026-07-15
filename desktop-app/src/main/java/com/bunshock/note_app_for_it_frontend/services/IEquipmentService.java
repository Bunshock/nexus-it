package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.SnValidation;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;

public interface IEquipmentService {

    List<EquipmentType> getAllTypes();

    List<EquipmentBrand> getBrandsForType(int typeId);

    List<EquipmentBrand> getAllBrands();

    List<EquipmentModel> getModelsForBrandAndType(int brandId, int typeId);

    List<EquipmentProvider> getAllProviders();

    Optional<SnValidation> getSnValidation(int modelId);

    List<SnValidationRow> getAllSnValidationRows();

    void upsertSnValidation(int modelId, String regex, boolean active);

    void addType(String name, boolean isAsset);

    void addBrand(String name);

    void addBrandForType(String brandName, int typeId);

    void addModel(String name, int brandId, int typeId);

    void addProvider(String name);

    void removeType(int typeId);

    void removeBrand(int brandId);

    void removeModel(int modelId);

    void removeProvider(int providerId);

    void renameType(int typeId, String newName);

    void setRequiresSerial(int typeId, boolean requiresSerial);

    void renameBrand(int brandId, String newName);

    void renameModel(int modelId, String newName);

    void renameProvider(int providerId, String newName);
}
