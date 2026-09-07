package com.bunshock.note_app_for_it.notes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** §5.1 item shape, v1: no external {@code externalItemId}/{@code externalStatusAtAddTime} — no adapter yet. */
public record NoteItemRequest(
        @NotBlank String kind, // "ASSET" | "COUNTABLE"
        @NotNull Integer typeId,
        @NotNull Integer brandId,
        @NotNull Integer modelId,
        String serialNumber,
        String af,
        Integer quantity,
        String observations,
        Boolean modifiesStock, // null treated as true — matches the desktop app's own default
        String modifiesStockReason) {

    public boolean isAsset() {
        return "ASSET".equalsIgnoreCase(kind);
    }

    public boolean effectiveModifiesStock() {
        return modifiesStock == null || modifiesStock;
    }
}
