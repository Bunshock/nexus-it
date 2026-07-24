package com.bunshock.note_app_for_it_frontend.models;

public class EquipmentModel {
    private final int id;
    private final Integer brandTypeId;
    private final String name;

    public EquipmentModel(int id, Integer brandTypeId, String name) {
        this.id = id;
        this.brandTypeId = brandTypeId;
        this.name = name;
    }

    public int getId() { return id; }

    // Null means this is the single global "Genérico / Otro" model — not scoped to any
    // particular brand+type link, offered for every combination regardless.
    public Integer getBrandTypeId() { return brandTypeId; }

    public boolean isGlobalGeneric() { return brandTypeId == null; }

    public String getName() { return name; }

    @Override
    public String toString() { return name; }
}
