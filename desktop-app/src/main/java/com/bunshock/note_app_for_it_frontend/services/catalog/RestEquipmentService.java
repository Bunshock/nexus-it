package com.bunshock.note_app_for_it_frontend.services.catalog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.catalog.Sede;
import com.bunshock.note_app_for_it_frontend.models.catalog.SedeShippingInfo;
import com.bunshock.note_app_for_it_frontend.models.catalog.SnValidation;
import com.bunshock.note_app_for_it_frontend.models.catalog.SnValidationRow;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareClient;
import com.bunshock.note_app_for_it_frontend.services.core.MiddlewareException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * {@code IEquipmentService} over the middleware's {@code /api/v1/catalog} endpoints (Phase B).
 *
 * <p><b>Browse reads are stale-while-revalidate cached</b> ({@value #TTL_MS} ms): a fresh entry is
 * returned from memory; a stale one is still returned immediately while a background thread
 * refreshes it; a cold entry blocks once. Any write ({@code add*}/{@code rename*}/{@code remove*}/
 * {@code upsertSnValidation}) clears the whole cache, so this app's own edits are never stale, and
 * a navigation that re-reads always gets fresh data. Stock reads are always live (no cache) — they
 * change more often and callers already re-read them after every edit.
 */
public class RestEquipmentService implements IEquipmentService {

    private static final long TTL_MS = 30_000;
    private static final String BASE = "/api/v1/catalog";

    private final MiddlewareClient client;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public RestEquipmentService(MiddlewareClient client) {
        this.client = client;
    }

    private record Entry(Object value, long fetchedAt) {}

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> fetch) {
        Entry e = cache.get(key);
        long now = System.currentTimeMillis();
        if (e == null) {
            T v = fetch.get();
            cache.put(key, new Entry(v, now));
            return v;
        }
        if (now - e.fetchedAt() > TTL_MS && refreshing.add(key)) {
            Thread t = new Thread(() -> {
                try {
                    cache.put(key, new Entry(fetch.get(), System.currentTimeMillis()));
                } catch (RuntimeException stale) {
                    // keep serving the stale value; a later read retries
                } finally {
                    refreshing.remove(key);
                }
            }, "catalog-swr-" + key);
            t.setDaemon(true);
            t.start();
        }
        return (T) e.value();
    }

    private void invalidateAll() {
        cache.clear();
    }

    // ── browse (cached) ───────────────────────────────────────────────────

    @Override
    public List<EquipmentType> getAllTypes() {
        return cached("types", () -> client.get(BASE + "/types", new TypeReference<List<TypeDto>>() {})
                .stream().map(TypeDto::toModel).collect(Collectors.toList()));
    }

    @Override
    public List<EquipmentBrand> getBrandsForType(int typeId) {
        return cached("brands:type:" + typeId,
                () -> client.get(BASE + "/brands?typeId=" + typeId, new TypeReference<List<BrandDto>>() {})
                        .stream().map(BrandDto::toModel).collect(Collectors.toList()));
    }

    @Override
    public List<EquipmentBrand> getAllBrands() {
        return cached("brands:all", () -> client.get(BASE + "/brands", new TypeReference<List<BrandDto>>() {})
                .stream().map(BrandDto::toModel).collect(Collectors.toList()));
    }

    @Override
    public List<EquipmentModel> getModelsForBrandAndType(int brandId, int typeId) {
        return cached("models:" + brandId + ":" + typeId,
                () -> client.get(BASE + "/models?typeId=" + typeId + "&brandId=" + brandId,
                        new TypeReference<List<ModelDto>>() {})
                        .stream().map(ModelDto::toModel).collect(Collectors.toList()));
    }

    @Override
    public List<EquipmentProvider> getAllProviders() {
        return cached("providers", () -> client.get(BASE + "/providers", new TypeReference<List<BrandDto>>() {})
                .stream().map(d -> new EquipmentProvider(d.id(), d.name())).collect(Collectors.toList()));
    }

    @Override
    public List<Sede> getAllSedes() {
        return cached("sedes", () -> client.get(BASE + "/sedes", new TypeReference<List<BrandDto>>() {})
                .stream().map(d -> new Sede(d.id(), d.name())).collect(Collectors.toList()));
    }

    @Override
    public Optional<SedeShippingInfo> getSedeShippingInfo(int sedeId) {
        return Optional.ofNullable(cached("shipping:" + sedeId, () -> {
            try {
                ShippingDto d = client.get(BASE + "/sedes/" + sedeId + "/shipping-info", ShippingDto.class);
                return new SedeShippingInfo(d.id(), d.sedeId(), d.destinationLabel(), d.address(), d.recipients());
            } catch (MiddlewareException e) {
                if (e.getStatus() == 404) return null;
                throw e;
            }
        }));
    }

    @Override
    public Set<Integer> getSedeIdsWithShippingInfo() {
        return cached("shipping-ids",
                () -> client.get(BASE + "/sedes/shipping-info-ids", new TypeReference<Set<Integer>>() {}));
    }

    @Override
    public Optional<SnValidation> getSnValidation(int modelId) {
        return Optional.ofNullable(cached("sn:" + modelId, () -> {
            try {
                SnDto d = client.get(BASE + "/models/" + modelId + "/sn-validation", SnDto.class);
                return new SnValidation(d.modelId(), d.regexPattern(), d.active());
            } catch (MiddlewareException e) {
                if (e.getStatus() == 404) return null;
                throw e;
            }
        }));
    }

    @Override
    public List<SnValidationRow> getAllSnValidationRows() {
        return cached("sn-rows", () -> client.get(BASE + "/sn-validations", new TypeReference<List<SnRowDto>>() {})
                .stream()
                .map(d -> new SnValidationRow(d.modelId(), d.typeName(), d.brandName(), d.modelName(),
                        d.regexPattern(), d.active()))
                .collect(Collectors.toList()));
    }

    // ── stock (live, no cache) ────────────────────────────────────────────

    @Override
    public int getModelStock(int modelId, int brandId, int typeId, int sedeId) {
        return client.get(BASE + "/models/" + modelId + "/stock?brandId=" + brandId
                + "&typeId=" + typeId + "&sedeId=" + sedeId, StockDto.class).stock();
    }

    @Override
    public void setModelStock(int modelId, int brandId, int typeId, int sedeId, int stock) {
        setModelStock(modelId, brandId, typeId, sedeId, stock, "Ajuste de stock");
    }

    @Override
    public void setModelStock(int modelId, int brandId, int typeId, int sedeId, int stock, String reason) {
        client.put(BASE + "/models/" + modelId + "/stock?brandId=" + brandId
                + "&typeId=" + typeId + "&sedeId=" + sedeId, new StockBody(stock, reason));
        invalidateAll();
    }

    @Override
    public void adjustModelStock(int modelId, int brandId, int typeId, int sedeId, int delta) {
        // Approval-time stock movement is server-side on the middleware (POST /notes/{id}/approve);
        // nothing on the client calls this on the REST catalog service.
        throw new UnsupportedOperationException("stock adjustment is applied server-side by the middleware");
    }

    @Override
    public Map<Integer, Integer> getStockTotalsByType(Integer sedeId) {
        return client.get(query(BASE + "/stock-totals/by-type", "sedeId", sedeId),
                new TypeReference<Map<Integer, Integer>>() {});
    }

    @Override
    public Map<Integer, Integer> getStockTotalsByBrandForType(int typeId, Integer sedeId) {
        return client.get(query(BASE + "/stock-totals/by-brand", "typeId", typeId, "sedeId", sedeId),
                new TypeReference<Map<Integer, Integer>>() {});
    }

    @Override
    public Map<Integer, Integer> getStockTotalsByModelForBrandAndType(int brandId, int typeId, Integer sedeId) {
        return client.get(query(BASE + "/stock-totals/by-model", "typeId", typeId, "brandId", brandId, "sedeId", sedeId),
                new TypeReference<Map<Integer, Integer>>() {});
    }

    // ── writes (invalidate) ──────────────────────────────────────────────

    @Override
    public void addType(String name, boolean isAsset) {
        client.post(BASE + "/types", new AddTypeBody(name, isAsset), Void.class);
        invalidateAll();
    }

    @Override
    public void renameType(int typeId, String newName) {
        client.put(BASE + "/types/" + typeId, new NameBody(newName));
        invalidateAll();
    }

    @Override
    public void setRequiresSerial(int typeId, boolean requiresSerial) {
        client.put(BASE + "/types/" + typeId + "/requires-serial", new RequiresSerialBody(requiresSerial));
        invalidateAll();
    }

    @Override
    public void removeType(int typeId) {
        client.delete(BASE + "/types/" + typeId);
        invalidateAll();
    }

    @Override
    public void addBrand(String name) {
        client.post(BASE + "/brands", new NameBody(name), Void.class);
        invalidateAll();
    }

    @Override
    public void addBrandForType(String brandName, int typeId) {
        client.post(BASE + "/brands?typeId=" + typeId, new NameBody(brandName), Void.class);
        invalidateAll();
    }

    @Override
    public void renameBrand(int brandId, String newName) {
        client.put(BASE + "/brands/" + brandId, new NameBody(newName));
        invalidateAll();
    }

    @Override
    public void removeBrand(int brandId) {
        client.delete(BASE + "/brands/" + brandId);
        invalidateAll();
    }

    @Override
    public void addModel(String name, int brandId, int typeId) {
        client.post(BASE + "/models?typeId=" + typeId + "&brandId=" + brandId, new NameBody(name), Void.class);
        invalidateAll();
    }

    @Override
    public void renameModel(int modelId, String newName) {
        client.put(BASE + "/models/" + modelId, new NameBody(newName));
        invalidateAll();
    }

    @Override
    public void removeModel(int modelId) {
        client.delete(BASE + "/models/" + modelId);
        invalidateAll();
    }

    @Override
    public void upsertSnValidation(int modelId, String regex, boolean active) {
        client.put(BASE + "/models/" + modelId + "/sn-validation", new SnUpdateBody(regex, active));
        invalidateAll();
    }

    // ── helpers / DTOs ───────────────────────────────────────────────────

    private static String query(String base, Object... kv) {
        StringBuilder sb = new StringBuilder(base);
        char sep = '?';
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) continue;
            sb.append(sep).append(kv[i]).append('=')
              .append(URLEncoder.encode(String.valueOf(kv[i + 1]), StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TypeDto(int id, String name, boolean isAsset, boolean requiresSerial) {
        EquipmentType toModel() { return new EquipmentType(id, name, isAsset, requiresSerial); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrandDto(int id, String name) {
        EquipmentBrand toModel() { return new EquipmentBrand(id, name); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelDto(int id, Integer brandTypeId, String name) {
        EquipmentModel toModel() { return new EquipmentModel(id, brandTypeId, name); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ShippingDto(int id, int sedeId, String destinationLabel, String address, String recipients) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SnDto(int modelId, String regexPattern, boolean active) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SnRowDto(int modelId, String typeName, String brandName, String modelName,
            String regexPattern, boolean active) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StockDto(int stock) {}

    private record NameBody(String name) {}
    private record AddTypeBody(String name, boolean isAsset) {}
    private record RequiresSerialBody(boolean requiresSerial) {}
    private record StockBody(Integer stock, String reason) {}
    private record SnUpdateBody(String regexPattern, boolean active) {}
}
