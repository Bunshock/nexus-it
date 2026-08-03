package com.bunshock.note_app_for_it_frontend.services;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.Sede;
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
    private final List<EquipmentProvider> providers = new ArrayList<>();
    private final List<Sede> sedes = new ArrayList<>();
    private final List<int[]> modelStocks = new ArrayList<>(); // [brandTypeId, modelId, sedeId, stock]

    private int nextTypeId = 1000;
    private int nextBrandId = 1000;
    private int nextTypeBrandId = 1000;
    private int nextModelId = 1000;
    private int nextProviderId = 1000;
    private int nextSedeId = 1000;

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
            boolean requiresSerial = n.has("requiresSerial") && n.get("requiresSerial").asBoolean();
            types.add(new EquipmentType(id, n.get("name").asText(), n.get("isAsset").asBoolean(), requiresSerial));
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
                snValidations.add(new SnValidation(
                    n.get("modelId").asInt(),
                    regex,
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
    public List<EquipmentBrand> getAllBrands() {
        return List.copyOf(brands);
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
    public List<EquipmentProvider> getAllProviders() {
        return List.copyOf(providers);
    }

    @Override
    public List<Sede> getAllSedes() {
        return List.copyOf(sedes);
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
        snValidations.add(new SnValidation(modelId, r, active));
    }

    @Override
    public void addType(String name, boolean isAsset) {
        types.add(new EquipmentType(nextTypeId++, name, isAsset, false));
    }

    @Override
    public void addBrand(String name) {
        brands.add(new EquipmentBrand(nextBrandId++, name));
    }

    @Override
    public void addBrandForType(String brandName, int typeId) {
        String trimmed = brandName.trim();
        EquipmentBrand existing = brands.stream()
            .filter(b -> b.getName().equalsIgnoreCase(trimmed))
            .findFirst().orElse(null);
        int brandId;
        if (existing == null) {
            brandId = nextBrandId++;
            brands.add(new EquipmentBrand(brandId, trimmed));
        } else {
            brandId = existing.getId();
        }
        boolean linkExists = typeBrands.stream()
            .anyMatch(tb -> tb[1] == typeId && tb[2] == brandId);
        if (!linkExists) {
            typeBrands.add(new int[]{nextTypeBrandId++, typeId, brandId});
        }
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

    // Not part of IEquipmentService anymore (Provider/Sede management moved to direct SQL, see
    // Base de Datos) — kept test-only so callers can still seed data without a real database.
    public void addProvider(String name) {
        providers.add(new EquipmentProvider(nextProviderId++, name.trim()));
    }

    public void addSede(String name) {
        sedes.add(new Sede(nextSedeId++, name.trim()));
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

    @Override
    public void renameType(int typeId, String newName) {
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).getId() == typeId) {
                EquipmentType old = types.get(i);
                types.set(i, new EquipmentType(old.getId(), newName.trim(), old.isAsset(), old.isRequiresSerial()));
                return;
            }
        }
    }

    @Override
    public void setRequiresSerial(int typeId, boolean requiresSerial) {
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).getId() == typeId) {
                EquipmentType old = types.get(i);
                types.set(i, new EquipmentType(old.getId(), old.getName(), old.isAsset(), requiresSerial));
                return;
            }
        }
    }

    @Override
    public void renameBrand(int brandId, String newName) {
        for (int i = 0; i < brands.size(); i++) {
            if (brands.get(i).getId() == brandId) {
                brands.set(i, new EquipmentBrand(brandId, newName.trim()));
                return;
            }
        }
    }

    @Override
    public void renameModel(int modelId, String newName) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).getId() == modelId) {
                EquipmentModel old = models.get(i);
                models.set(i, new EquipmentModel(old.getId(), old.getBrandTypeId(), newName.trim()));
                return;
            }
        }
    }

    // ── Stock — deliberately follows this class's existing (narrower) scoping model, which,
    // unlike SqliteEquipmentService, doesn't union in a global brand_type_id-null generic model
    // for getModelsForBrandAndType() — a pre-existing gap, not something this feature fixes.

    @Override
    public int getModelStock(int modelId, int brandId, int typeId, int sedeId) {
        int linkId = findTypeBrandLinkId(brandId, typeId);
        if (linkId == -1) return 0;
        return modelStocks.stream()
            .filter(s -> s[0] == linkId && s[1] == modelId && s[2] == sedeId)
            .mapToInt(s -> s[3])
            .findFirst().orElse(0);
    }

    @Override
    public void setModelStock(int modelId, int brandId, int typeId, int sedeId, int stock) {
        int linkId = ensureTypeBrandLink(brandId, typeId);
        for (int[] s : modelStocks) {
            if (s[0] == linkId && s[1] == modelId && s[2] == sedeId) {
                s[3] = stock;
                return;
            }
        }
        modelStocks.add(new int[]{linkId, modelId, sedeId, stock});
    }

    // sedeId null means "every Sede combined" (summed), matching SqliteEquipmentService's
    // convention above.
    @Override
    public Map<Integer, Integer> getStockTotalsByType(Integer sedeId) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int[] s : modelStocks) {
            if (sedeId != null && s[2] != sedeId) continue;
            typeBrands.stream().filter(tb -> tb[0] == s[0]).findFirst()
                .ifPresent(tb -> result.merge(tb[1], s[3], Integer::sum));
        }
        return result;
    }

    @Override
    public Map<Integer, Integer> getStockTotalsByBrandForType(int typeId, Integer sedeId) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int[] s : modelStocks) {
            if (sedeId != null && s[2] != sedeId) continue;
            typeBrands.stream().filter(tb -> tb[0] == s[0] && tb[1] == typeId).findFirst()
                .ifPresent(tb -> result.merge(tb[2], s[3], Integer::sum));
        }
        return result;
    }

    @Override
    public Map<Integer, Integer> getStockTotalsByModelForBrandAndType(int brandId, int typeId, Integer sedeId) {
        Map<Integer, Integer> result = new HashMap<>();
        int linkId = findTypeBrandLinkId(brandId, typeId);
        if (linkId == -1) return result;
        for (int[] s : modelStocks) {
            if (s[0] == linkId && (sedeId == null || s[2] == sedeId)) result.merge(s[1], s[3], Integer::sum);
        }
        return result;
    }

    private int findTypeBrandLinkId(int brandId, int typeId) {
        return typeBrands.stream()
            .filter(tb -> tb[1] == typeId && tb[2] == brandId)
            .mapToInt(tb -> tb[0])
            .findFirst().orElse(-1);
    }

    private int ensureTypeBrandLink(int brandId, int typeId) {
        int existing = findTypeBrandLinkId(brandId, typeId);
        if (existing != -1) return existing;
        int id = nextTypeBrandId++;
        typeBrands.add(new int[]{id, typeId, brandId});
        return id;
    }
}
