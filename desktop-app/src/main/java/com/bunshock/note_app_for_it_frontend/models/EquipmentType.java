package com.bunshock.note_app_for_it_frontend.models;

public class EquipmentType {
    private final int id;
    private final String name;
    private final boolean isAsset;
    private final boolean requiresSerial;

    public EquipmentType(int id, String name, boolean isAsset, boolean requiresSerial) {
        this.id = id;
        this.name = name;
        this.isAsset = isAsset;
        this.requiresSerial = requiresSerial;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public boolean isAsset() { return isAsset; }
    public boolean isRequiresSerial() { return requiresSerial; }

    @Override
    public String toString() { return name; }
}
