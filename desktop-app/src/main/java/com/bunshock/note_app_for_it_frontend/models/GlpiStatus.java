package com.bunshock.note_app_for_it_frontend.models;

public enum GlpiStatus {
    PENDING, SYNCED, REJECTED, N_A;

    public static GlpiStatus fromString(String s) {
        if (s == null) return N_A;
        return switch (s) {
            case "PENDING"  -> PENDING;
            case "SYNCED"   -> SYNCED;
            case "REJECTED" -> REJECTED;
            default         -> N_A;
        };
    }

    public String toDbString() { return name(); }
}
