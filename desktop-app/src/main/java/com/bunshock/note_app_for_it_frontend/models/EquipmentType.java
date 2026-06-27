package com.bunshock.note_app_for_it_frontend.models;

public class EquipmentType {
    private final int id;
    private final String name;
    private final boolean isAsset;

    public EquipmentType(int id, String name, boolean isAsset) {
        this.id = id;
        this.name = name;
        this.isAsset = isAsset;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public boolean isAsset() { return isAsset; }

    @Override
    public String toString() { return name; }
}
