package com.bunshock.note_app_for_it.notes.dto;

import java.util.List;

public record NoteItemResponse(
        int id, int typeId, int brandId, int modelId,
        String typeName, String brandName, String modelName,
        String serialNumber, String af, int quantity,
        String observations, boolean modifiesStock, String modifiesStockReason,
        boolean asset,
        String glpiStatus, String glpiRejectionReason, String glpiStatusUpdatedAt,
        String glpiReturnStatus, String glpiReturnRejectionReason, String glpiReturnStatusUpdatedAt,
        String returnStatus, String returnRejectionReason, String returnStatusUpdatedAt,
        int returnedQuantity, int lostQuantity,
        List<AllocationBatchResponse> returnedBatches, List<AllocationBatchResponse> lostBatches) {

    /** One NOTE_ITEM_RETURN_ALLOCATION row — a countable item's individual RETURNED or LOST
     * event, each with its own quantity/reason/timestamp (the aggregate returnedQuantity/
     * lostQuantity above can each represent several merged events with no single correct moment
     * to show). Never populated for an asset item, which has no partial-allocation concept. */
    public record AllocationBatchResponse(int quantity, String reason, String updatedAt) {
    }
}
