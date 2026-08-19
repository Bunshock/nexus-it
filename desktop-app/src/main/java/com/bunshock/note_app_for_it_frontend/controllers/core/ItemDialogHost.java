package com.bunshock.note_app_for_it_frontend.controllers.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.catalog.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.catalog.CountableItem;
import com.bunshock.note_app_for_it_frontend.services.catalog.IEquipmentService;

// Lets ItemDialogController (the shared Add/Edit Equipo popup) report a new item back to
// whichever screen opened it, without being hard-typed to a single controller class.
public interface ItemDialogHost {
    void addAsset(AssetItem item);
    void addCountable(CountableItem item);

    // Aggregates assetList/countableList by (typeId, brandId, modelId) — the same model can
    // appear on multiple rows — and compares each requested total against current stock at
    // sedeId. Returns one human-readable line per short model, or an empty list if everything
    // requested fits. Items flagged "no modifica stock" are excluded from the requested total.
    static List<String> computeStockShortages(List<AssetItem> assetList, List<CountableItem> countableList,
            IEquipmentService equipmentService, int sedeId) {
        record StockKey(int typeId, int brandId, int modelId) {}
        Map<StockKey, Integer> requested = new LinkedHashMap<>();
        Map<StockKey, String> labels = new LinkedHashMap<>();
        for (AssetItem a : assetList) {
            if (!a.isModifiesStock()) continue;
            StockKey key = new StockKey(a.getTypeId(), a.getBrandId(), a.getModelId());
            requested.merge(key, 1, Integer::sum);
            labels.putIfAbsent(key, a.getType().get() + " " + a.getBrand().get() + " " + a.getModel().get());
        }
        for (CountableItem item : countableList) {
            if (!item.isModifiesStock()) continue;
            StockKey key = new StockKey(item.getTypeId(), item.getBrandId(), item.getModelId());
            requested.merge(key, item.getQuantity().get(), Integer::sum);
            labels.putIfAbsent(key, item.getType().get() + " " + item.getBrand().get() + " " + item.getModel().get());
        }
        if (requested.isEmpty()) return List.of();

        List<String> shortages = new ArrayList<>();
        for (var entry : requested.entrySet()) {
            StockKey key = entry.getKey();
            int available = equipmentService.getModelStock(key.modelId(), key.brandId(), key.typeId(), sedeId);
            if (available < entry.getValue()) {
                shortages.add(labels.get(key) + " (solicita " + entry.getValue() + ", disponible " + available + ")");
            }
        }
        return shortages;
    }
}
