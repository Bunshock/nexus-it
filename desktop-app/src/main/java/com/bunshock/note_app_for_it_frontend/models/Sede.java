package com.bunshock.note_app_for_it_frontend.models;

public class Sede {
    private final int id;
    private final String name;

    public Sede(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() { return id; }
    public String getName() { return name; }

    @Override
    public String toString() { return name; }
}
