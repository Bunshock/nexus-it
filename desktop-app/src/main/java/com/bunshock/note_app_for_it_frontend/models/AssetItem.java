package com.bunshock.note_app_for_it_frontend.models;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class AssetItem extends EquipmentItem {
    private final StringProperty serial;
    private final StringProperty af;

    public AssetItem(String type, String brand, String model, String observations, String serial, String af,
            int typeId, int brandId, int modelId) {
        this(type, brand, model, observations, serial, af, typeId, brandId, modelId, true);
    }

    public AssetItem(String type, String brand, String model, String observations, String serial, String af,
            int typeId, int brandId, int modelId, boolean modifiesStock) {
        super(type, brand, model, observations, typeId, brandId, modelId, modifiesStock);
        this.serial = new SimpleStringProperty(serial);
        this.af = new SimpleStringProperty(af);
    }

    public StringProperty getSerial() { return serial; }
    public StringProperty getAf() { return af; }
}
