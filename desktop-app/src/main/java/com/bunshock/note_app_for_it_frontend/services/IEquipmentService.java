package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.SnValidation;

public interface IEquipmentService {

    List<EquipmentType> getAllTypes();

    List<EquipmentBrand> getBrandsForType(int typeId);

    List<EquipmentModel> getModelsForBrandAndType(int brandId, int typeId);

    Optional<SnValidation> getSnValidation(int modelId);

    void addType(String name, boolean isAsset);

    void addBrand(String name);

    void addModel(String name, int brandId, int typeId);

    void removeType(int typeId);

    void removeBrand(int brandId);

    void removeModel(int modelId);
}
