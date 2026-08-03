package com.bunshock.note_app_for_it_frontend.models;

/**
 * One recorded LOST allocation batch for a countable item under active return tracking (see
 * NOTE_ITEM_RETURN_ALLOCATION). Only LOST batches are surfaced individually — each one needs its
 * own reason shown separately, since a countable can be marked lost in several unrelated batches
 * over time (e.g. 1 lost today for one reason, 2 more lost next week for another). RETURNED
 * batches have no "why" to attach, so they're shown as one aggregate quantity instead
 * (NoteReportItem.getReturnedQuantity()), not as individual entries.
 */
public class ReturnAllocationBatch {
    private int quantity;
    private String reason;
    private String updatedAt;

    public ReturnAllocationBatch() {}

    public ReturnAllocationBatch(int quantity, String reason, String updatedAt) {
        this.quantity = quantity;
        this.reason = reason;
        this.updatedAt = updatedAt;
    }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
