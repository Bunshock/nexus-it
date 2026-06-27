package com.bunshock.note_app_for_it_frontend.models;

public class EquipmentModel {
    private final int id;
    private final int brandTypeId;
    private final String name;

    public EquipmentModel(int id, int brandTypeId, String name) {
        this.id = id;
        this.brandTypeId = brandTypeId;
        this.name = name;
    }

    public int getId() { return id; }
    public int getBrandTypeId() { return brandTypeId; }
    public String getName() { return name; }

    @Override
    public String toString() { return name; }
}
