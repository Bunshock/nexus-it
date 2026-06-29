package com.bunshock.note_app_for_it_frontend.services;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.SnValidation;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MockEquipmentService implements IEquipmentService {

    private static final String MOCK_FILE = "config/mock-equipment.json";

    private final List<EquipmentType> types = new ArrayList<>();
    private final List<EquipmentBrand> brands = new ArrayList<>();
    private final List<int[]> typeBrands = new ArrayList<>();
    private final List<EquipmentModel> models = new ArrayList<>();
    private final List<SnValidation> snValidations = new ArrayList<>();

    private int nextTypeId = 1000;
    private int nextBrandId = 1000;
    private int nextTypeBrandId = 1000;
    private int nextModelId = 1000;

    public MockEquipmentService() {
        try {
            load();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load mock equipment data: " + e.getMessage(), e);
        }
    }

    private void load() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new File(MOCK_FILE));

        for (JsonNode n : root.get("types")) {
            int id = n.get("id").asInt();
            types.add(new EquipmentType(id, n.get("name").asText(), n.get("isAsset").asBoolean()));
            if (id >= nextTypeId) nextTypeId = id + 1;
        }

        for (JsonNode n : root.get("brands")) {
            int id = n.get("id").asInt();
            brands.add(new EquipmentBrand(id, n.get("name").asText()));
            if (id >= nextBrandId) nextBrandId = id + 1;
        }

        for (JsonNode n : root.get("typeBrands")) {
            int id = n.get("id").asInt();
            typeBrands.add(new int[]{id, n.get("typeId").asInt(), n.get("brandId").asInt()});
            if (id >= nextTypeBrandId) nextTypeBrandId = id + 1;
        }

        for (JsonNode n : root.get("models")) {
            int id = n.get("id").asInt();
            models.add(new EquipmentModel(id, n.get("brandTypeId").asInt(), n.get("name").asText()));
            if (id >= nextModelId) nextModelId = id + 1;
        }

        if (root.has("snValidations")) {
            for (JsonNode n : root.get("snValidations")) {
                String regex = n.has("regexPattern") && !n.get("regexPattern").isNull()
                    ? n.get("regexPattern").asText() : null;
                String desc = n.has("description") && !n.get("description").isNull()
                    ? n.get("description").asText() : null;
                snValidations.add(new SnValidation(
                    n.get("modelId").asInt(),
                    regex,
                    desc,
                    n.get("isActive").asBoolean()
                ));
            }
        }
    }

    @Override
    public List<EquipmentType> getAllTypes() {
        return List.copyOf(types);
    }

    @Override
    public List<EquipmentBrand> getBrandsForType(int typeId) {
        List<Integer> brandIds = typeBrands.stream()
            .filter(tb -> tb[1] == typeId)
            .map(tb -> tb[2])
            .toList();
        return brands.stream()
            .filter(b -> brandIds.contains(b.getId()))
            .toList();
    }

    @Override
    public List<EquipmentModel> getModelsForBrandAndType(int brandId, int typeId) {
        int brandTypeId = typeBrands.stream()
            .filter(tb -> tb[1] == typeId && tb[2] == brandId)
            .mapToInt(tb -> tb[0])
            .findFirst()
            .orElse(-1);

        if (brandTypeId == -1) return List.of();

        return models.stream()
            .filter(m -> m.getBrandTypeId() == brandTypeId)
            .toList();
    }

    @Override
    public Optional<SnValidation> getSnValidation(int modelId) {
        return snValidations.stream()
            .filter(v -> v.getModelId() == modelId && v.isActive())
            .findFirst();
    }

    @Override
    public List<SnValidationRow> getAllSnValidationRows() {
        List<SnValidationRow> rows = new ArrayList<>();
        for (EquipmentType type : getAllTypes()) {
            if (!type.isAsset()) continue;
            for (EquipmentBrand brand : getBrandsForType(type.getId())) {
                for (EquipmentModel model : getModelsForBrandAndType(brand.getId(), type.getId())) {
                    SnValidation sv = snValidations.stream()
                        .filter(v -> v.getModelId() == model.getId())
                        .findFirst().orElse(null);
                    rows.add(new SnValidationRow(
                        model.getId(), type.getName(), brand.getName(), model.getName(),
                        sv != null ? sv.getRegexPattern() : null,
                        sv != null && sv.isActive()
                    ));
                }
            }
        }
        return rows;
    }

    @Override
    public void upsertSnValidation(int modelId, String regex, boolean active) {
        snValidations.removeIf(v -> v.getModelId() == modelId);
        String r = (regex == null || regex.isBlank()) ? null : regex.trim();
        snValidations.add(new SnValidation(modelId, r, null, active));
    }

    @Override
    public void addType(String name, boolean isAsset) {
        types.add(new EquipmentType(nextTypeId++, name, isAsset));
    }

    @Override
    public void addBrand(String name) {
        brands.add(new EquipmentBrand(nextBrandId++, name));
    }

    @Override
    public void addModel(String name, int brandId, int typeId) {
        int brandTypeId = typeBrands.stream()
            .filter(tb -> tb[1] == typeId && tb[2] == brandId)
            .mapToInt(tb -> tb[0])
            .findFirst()
            .orElseGet(() -> {
                int newId = nextTypeBrandId++;
                typeBrands.add(new int[]{newId, typeId, brandId});
                return newId;
            });
        models.add(new EquipmentModel(nextModelId++, brandTypeId, name));
    }

    @Override
    public void removeType(int typeId) {
        types.removeIf(t -> t.getId() == typeId);
    }

    @Override
    public void removeBrand(int brandId) {
        brands.removeIf(b -> b.getId() == brandId);
    }

    @Override
    public void removeModel(int modelId) {
        models.removeIf(m -> m.getId() == modelId);
    }
}
