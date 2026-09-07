package com.bunshock.note_app_for_it.notes.dto;

public record NoteItemResponse(
        int id, int typeId, int brandId, int modelId,
        String typeName, String brandName, String modelName,
        String serialNumber, String af, int quantity,
        String observations, boolean modifiesStock, String modifiesStockReason,
        boolean asset,
        String glpiStatus, String glpiRejectionReason, String glpiStatusUpdatedAt,
        String glpiReturnStatus, String glpiReturnRejectionReason, String glpiReturnStatusUpdatedAt,
        String returnStatus, String returnRejectionReason, String returnStatusUpdatedAt,
        int returnedQuantity, int lostQuantity) {
}
