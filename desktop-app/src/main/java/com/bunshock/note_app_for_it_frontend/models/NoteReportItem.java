package com.bunshock.note_app_for_it_frontend.models;

import java.util.ArrayList;
import java.util.List;

public class NoteReportItem {
    private int id;
    private int typeId;
    private int brandId;
    private int modelId;
    private String typeName;
    private String brandName;
    private String modelName;
    private String serialNumber;
    private String af;
    private int quantity;
    private String observations;
    private boolean isAsset;
    private boolean modifiesStock = true;
    private String modifiesStockReason;
    private GlpiStatus glpiStatus = GlpiStatus.N_A;
    private String glpiRejectionReason;
    private String glpiStatusUpdatedAt;
    private ReturnStatus returnStatus = ReturnStatus.N_A;
    private String returnRejectionReason;
    private String returnStatusUpdatedAt;
    // A second, independent GLPI dimension — only ever populated for a returnable Provider note's
    // asset item, and only once its return has actually been validated (RETURNED). See
    // NOTE_ITEM_GLPI_RETURN_TRACKING's own doc in DatabaseService for why this can't just reuse
    // glpiStatus above (GLPI sync is one-way/no-revert, so the original sync-out can't be undone
    // to reflect the item coming back — this tracks the separate "synced back in" event).
    private GlpiStatus glpiReturnStatus = GlpiStatus.N_A;
    private String glpiReturnRejectionReason;
    private String glpiReturnStatusUpdatedAt;
    // Populated only for a countable item under active return tracking (Préstamo, or a
    // returnable-Motivo Provider note) — a countable can be resolved in partial batches (e.g. 5
    // loaned, 3 returned, 1 lost, 1 still pending), which a single ReturnStatus can't express.
    // Asset items never set these (they stay 0); their whole-item state is returnStatus above.
    private int returnedQuantity;
    private int lostQuantity;
    // Individual LOST and RETURNED batches — each is one real event with its own single
    // timestamp, so both can be displayed per-batch with a full, meaningful "when did this
    // happen" rather than the getReturnedQuantity()/getLostQuantity() sums above (which can each
    // represent several separate events merged into one number, with no single correct moment to
    // show for the total).
    private List<ReturnAllocationBatch> lostBatches = new ArrayList<>();
    private List<ReturnAllocationBatch> returnedBatches = new ArrayList<>();

    public NoteReportItem() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getTypeId() { return typeId; }
    public void setTypeId(int typeId) { this.typeId = typeId; }

    public int getBrandId() { return brandId; }
    public void setBrandId(int brandId) { this.brandId = brandId; }

    public int getModelId() { return modelId; }
    public void setModelId(int modelId) { this.modelId = modelId; }

    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }

    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getAf() { return af; }
    public void setAf(String af) { this.af = af; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public String getObservations() { return observations; }
    public void setObservations(String observations) { this.observations = observations; }

    public boolean isAsset() { return isAsset; }
    public void setAsset(boolean asset) { isAsset = asset; }

    public boolean isModifiesStock() { return modifiesStock; }
    public void setModifiesStock(boolean modifiesStock) { this.modifiesStock = modifiesStock; }

    public String getModifiesStockReason() { return modifiesStockReason; }
    public void setModifiesStockReason(String modifiesStockReason) { this.modifiesStockReason = modifiesStockReason; }

    public GlpiStatus getGlpiStatus() { return glpiStatus; }
    public void setGlpiStatus(GlpiStatus glpiStatus) { this.glpiStatus = glpiStatus; }

    public String getGlpiRejectionReason() { return glpiRejectionReason; }
    public void setGlpiRejectionReason(String glpiRejectionReason) { this.glpiRejectionReason = glpiRejectionReason; }

    public String getGlpiStatusUpdatedAt() { return glpiStatusUpdatedAt; }
    public void setGlpiStatusUpdatedAt(String glpiStatusUpdatedAt) { this.glpiStatusUpdatedAt = glpiStatusUpdatedAt; }

    public ReturnStatus getReturnStatus() { return returnStatus; }
    public void setReturnStatus(ReturnStatus returnStatus) { this.returnStatus = returnStatus; }

    public String getReturnRejectionReason() { return returnRejectionReason; }
    public void setReturnRejectionReason(String returnRejectionReason) { this.returnRejectionReason = returnRejectionReason; }

    public String getReturnStatusUpdatedAt() { return returnStatusUpdatedAt; }
    public void setReturnStatusUpdatedAt(String returnStatusUpdatedAt) { this.returnStatusUpdatedAt = returnStatusUpdatedAt; }

    public GlpiStatus getGlpiReturnStatus() { return glpiReturnStatus; }
    public void setGlpiReturnStatus(GlpiStatus glpiReturnStatus) { this.glpiReturnStatus = glpiReturnStatus; }

    public String getGlpiReturnRejectionReason() { return glpiReturnRejectionReason; }
    public void setGlpiReturnRejectionReason(String glpiReturnRejectionReason) { this.glpiReturnRejectionReason = glpiReturnRejectionReason; }

    public String getGlpiReturnStatusUpdatedAt() { return glpiReturnStatusUpdatedAt; }
    public void setGlpiReturnStatusUpdatedAt(String glpiReturnStatusUpdatedAt) { this.glpiReturnStatusUpdatedAt = glpiReturnStatusUpdatedAt; }

    public int getReturnedQuantity() { return returnedQuantity; }
    public void setReturnedQuantity(int returnedQuantity) { this.returnedQuantity = returnedQuantity; }

    public int getLostQuantity() { return lostQuantity; }
    public void setLostQuantity(int lostQuantity) { this.lostQuantity = lostQuantity; }

    /** Remaining quantity not yet resolved to RETURNED or LOST — only meaningful for a countable
     * item under active return tracking (0 for everything else, including assets). */
    public int getReturnPendingQuantity() {
        int pending = quantity - returnedQuantity - lostQuantity;
        return Math.max(pending, 0);
    }

    public List<ReturnAllocationBatch> getLostBatches() { return lostBatches; }
    public void setLostBatches(List<ReturnAllocationBatch> lostBatches) { this.lostBatches = lostBatches; }

    public List<ReturnAllocationBatch> getReturnedBatches() { return returnedBatches; }
    public void setReturnedBatches(List<ReturnAllocationBatch> returnedBatches) { this.returnedBatches = returnedBatches; }
}
