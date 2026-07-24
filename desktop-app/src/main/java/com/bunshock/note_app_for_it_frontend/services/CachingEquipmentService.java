package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.Sede;
import com.bunshock.note_app_for_it_frontend.models.SnValidation;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;

public class CachingEquipmentService implements IEquipmentService {

    private final IEquipmentService primary;
    private final IEquipmentService local;

    public CachingEquipmentService(IEquipmentService primary, IEquipmentService local) {
        this.primary = primary;
        this.local = local;
    }

    // ── Reads: try primary, fall back to local ───────────────────────

    @Override
    public List<EquipmentType> getAllTypes() {
        try { return primary.getAllTypes(); } catch (Exception e) { return local.getAllTypes(); }
    }

    @Override
    public List<EquipmentBrand> getBrandsForType(int typeId) {
        try { return primary.getBrandsForType(typeId); } catch (Exception e) { return local.getBrandsForType(typeId); }
    }

    @Override
    public List<EquipmentModel> getModelsForBrandAndType(int brandId, int typeId) {
        try { return primary.getModelsForBrandAndType(brandId, typeId); }
        catch (Exception e) { return local.getModelsForBrandAndType(brandId, typeId); }
    }

    @Override
    public List<EquipmentBrand> getAllBrands() {
        try { return primary.getAllBrands(); } catch (Exception e) { return local.getAllBrands(); }
    }

    @Override
    public List<EquipmentProvider> getAllProviders() {
        try { return primary.getAllProviders(); } catch (Exception e) { return local.getAllProviders(); }
    }

    @Override
    public List<Sede> getAllSedes() {
        try { return primary.getAllSedes(); } catch (Exception e) { return local.getAllSedes(); }
    }

    @Override
    public Optional<SnValidation> getSnValidation(int modelId) {
        try { return primary.getSnValidation(modelId); } catch (Exception e) { return local.getSnValidation(modelId); }
    }

    @Override
    public List<SnValidationRow> getAllSnValidationRows() {
        try { return primary.getAllSnValidationRows(); } catch (Exception e) { return local.getAllSnValidationRows(); }
    }

    // ── Writes: primary first (fail loudly), then local best-effort ──

    @Override
    public void upsertSnValidation(int modelId, String regex, boolean active) {
        primary.upsertSnValidation(modelId, regex, active);
        try { local.upsertSnValidation(modelId, regex, active); } catch (Exception ignored) {}
    }

    @Override
    public void addType(String name, boolean isAsset) {
        primary.addType(name, isAsset);
        try { local.addType(name, isAsset); } catch (Exception ignored) {}
    }

    @Override
    public void addBrand(String name) {
        primary.addBrand(name);
        try { local.addBrand(name); } catch (Exception ignored) {}
    }

    @Override
    public void addBrandForType(String brandName, int typeId) {
        primary.addBrandForType(brandName, typeId);
        try { local.addBrandForType(brandName, typeId); } catch (Exception ignored) {}
    }

    @Override
    public void addModel(String name, int brandId, int typeId) {
        primary.addModel(name, brandId, typeId);
        try { local.addModel(name, brandId, typeId); } catch (Exception ignored) {}
    }

    @Override
    public void addProvider(String name) {
        primary.addProvider(name);
        try { local.addProvider(name); } catch (Exception ignored) {}
    }

    @Override
    public void addSede(String name) {
        primary.addSede(name);
        try { local.addSede(name); } catch (Exception ignored) {}
    }

    @Override
    public void removeType(int typeId) {
        primary.removeType(typeId);
        try { local.removeType(typeId); } catch (Exception ignored) {}
    }

    @Override
    public void removeBrand(int brandId) {
        primary.removeBrand(brandId);
        try { local.removeBrand(brandId); } catch (Exception ignored) {}
    }

    @Override
    public void removeModel(int modelId) {
        primary.removeModel(modelId);
        try { local.removeModel(modelId); } catch (Exception ignored) {}
    }

    @Override
    public void removeProvider(int providerId) {
        primary.removeProvider(providerId);
        try { local.removeProvider(providerId); } catch (Exception ignored) {}
    }

    @Override
    public void removeSede(int sedeId) {
        primary.removeSede(sedeId);
        try { local.removeSede(sedeId); } catch (Exception ignored) {}
    }

    @Override
    public void renameType(int typeId, String newName) {
        primary.renameType(typeId, newName);
        try { local.renameType(typeId, newName); } catch (Exception ignored) {}
    }

    @Override
    public void setRequiresSerial(int typeId, boolean requiresSerial) {
        primary.setRequiresSerial(typeId, requiresSerial);
        try { local.setRequiresSerial(typeId, requiresSerial); } catch (Exception ignored) {}
    }

    @Override
    public void renameBrand(int brandId, String newName) {
        primary.renameBrand(brandId, newName);
        try { local.renameBrand(brandId, newName); } catch (Exception ignored) {}
    }

    @Override
    public void renameModel(int modelId, String newName) {
        primary.renameModel(modelId, newName);
        try { local.renameModel(modelId, newName); } catch (Exception ignored) {}
    }

    @Override
    public void renameProvider(int providerId, String newName) {
        primary.renameProvider(providerId, newName);
        try { local.renameProvider(providerId, newName); } catch (Exception ignored) {}
    }

    @Override
    public void renameSede(int sedeId, String newName) {
        primary.renameSede(sedeId, newName);
        try { local.renameSede(sedeId, newName); } catch (Exception ignored) {}
    }
}
