package com.bunshock.note_app_for_it_frontend.models.history;

public enum ReturnStatus {
    PENDING, RETURNED, LOST, N_A;

    public static ReturnStatus fromString(String s) {
        if (s == null) return N_A;
        return switch (s) {
            case "PENDING"  -> PENDING;
            case "RETURNED" -> RETURNED;
            case "LOST"     -> LOST;
            default         -> N_A;
        };
    }

    public String toDbString() { return name(); }
}
