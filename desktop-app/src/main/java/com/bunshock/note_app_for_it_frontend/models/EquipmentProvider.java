package com.bunshock.note_app_for_it_frontend.models;

public class EquipmentProvider {
    private final int id;
    private final String name;

    public EquipmentProvider(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() { return id; }
    public String getName() { return name; }

    @Override
    public String toString() { return name; }
}
