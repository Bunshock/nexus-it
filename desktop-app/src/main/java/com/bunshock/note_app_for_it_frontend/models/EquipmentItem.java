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

    public EquipmentItem(String type, String brand, String model, String observations,
            int typeId, int brandId, int modelId) {
        this.type = new SimpleStringProperty(type);
        this.brand = new SimpleStringProperty(brand);
        this.model = new SimpleStringProperty(model);
        this.observations = new SimpleStringProperty(observations);
        this.typeId = typeId;
        this.brandId = brandId;
        this.modelId = modelId;
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
}
