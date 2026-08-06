package com.bunshock.note_app_for_it_frontend.models;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class EquipmentItem {
    private final StringProperty type;
    private final StringProperty brand;
    private final StringProperty model;
    private final StringProperty observations;
    private int typeId;
    private int brandId;
    private int modelId;
    private boolean modifiesStock;
    // Only ever non-null when modifiesStock is false — the technician-supplied justification
    // captured by ItemDialogController's confirmation popup, kept for admin audit purposes.
    private String modifiesStockReason;

    public EquipmentItem(String type, String brand, String model, String observations,
            int typeId, int brandId, int modelId) {
        this(type, brand, model, observations, typeId, brandId, modelId, true);
    }

    public EquipmentItem(String type, String brand, String model, String observations,
            int typeId, int brandId, int modelId, boolean modifiesStock) {
        this.type = new SimpleStringProperty(type);
        this.brand = new SimpleStringProperty(brand);
        this.model = new SimpleStringProperty(model);
        this.observations = new SimpleStringProperty(observations);
        this.typeId = typeId;
        this.brandId = brandId;
        this.modelId = modelId;
        this.modifiesStock = modifiesStock;
    }

    public StringProperty getType() { return type; }
    public StringProperty getBrand() { return brand; }
    public StringProperty getModel() { return model; }
    public StringProperty getObservations() { return observations; }

    public int getTypeId() { return typeId; }
    public void setTypeId(int typeId) { this.typeId = typeId; }

    public int getBrandId() { return brandId; }
    public void setBrandId(int brandId) { this.brandId = brandId; }

    public int getModelId() { return modelId; }
    public void setModelId(int modelId) { this.modelId = modelId; }

    public boolean isModifiesStock() { return modifiesStock; }
    public void setModifiesStock(boolean modifiesStock) { this.modifiesStock = modifiesStock; }

    public String getModifiesStockReason() { return modifiesStockReason; }
    public void setModifiesStockReason(String modifiesStockReason) { this.modifiesStockReason = modifiesStockReason; }
}
