package com.bunshock.note_app_for_it_frontend.models;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

public class CountableItem extends EquipmentItem {
    private final IntegerProperty quantity;

    public CountableItem(String type, String brand, String model, int quantity, String observations,
            int typeId, int brandId, int modelId) {
        super(type, brand, model, observations, typeId, brandId, modelId);
        this.quantity = new SimpleIntegerProperty(quantity);
    }

    public IntegerProperty getQuantity() { return quantity; }
}
