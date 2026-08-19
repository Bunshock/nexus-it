package com.bunshock.note_app_for_it_frontend.models.catalog;

// A Sede's saved Remito destination details — configured directly via SQL by a superadmin (see
// CLAUDE.md's Provider/Sede "no in-app CRUD" convention), read-only from the app's own side.
// Deprecated-flag versioned (see CLAUDE.md's Remito schema notes) — a superadmin edit deprecates
// the old row and inserts a new one rather than mutating in place, so id can be referenced by an
// already-saved NOTE_REMITO_SEDE row without that historical value ever silently changing.
public class SedeShippingInfo {
    private final int id;
    private final int sedeId;
    private final String destinationLabel;
    private final String address;
    private final String recipients;

    public SedeShippingInfo(int id, int sedeId, String destinationLabel, String address, String recipients) {
        this.id = id;
        this.sedeId = sedeId;
        this.destinationLabel = destinationLabel;
        this.address = address;
        this.recipients = recipients;
    }

    public int getId() { return id; }
    public int getSedeId() { return sedeId; }
    public String getDestinationLabel() { return destinationLabel; }
    public String getAddress() { return address; }
    public String getRecipients() { return recipients; }
}
