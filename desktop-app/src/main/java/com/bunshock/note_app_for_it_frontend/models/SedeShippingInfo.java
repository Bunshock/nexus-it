package com.bunshock.note_app_for_it_frontend.models;

// A Sede's saved Remito destination details — configured directly via SQL by a superadmin (see
// CLAUDE.md's Provider/Sede "no in-app CRUD" convention), read-only from the app's own side.
public class SedeShippingInfo {
    private final int sedeId;
    private final String destinationLabel;
    private final String address;
    private final String recipients;

    public SedeShippingInfo(int sedeId, String destinationLabel, String address, String recipients) {
        this.sedeId = sedeId;
        this.destinationLabel = destinationLabel;
        this.address = address;
        this.recipients = recipients;
    }

    public int getSedeId() { return sedeId; }
    public String getDestinationLabel() { return destinationLabel; }
    public String getAddress() { return address; }
    public String getRecipients() { return recipients; }
}
