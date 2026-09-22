package com.bunshock.note_app_for_it.notes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * §5.1 item shape, v1: no external {@code externalItemId}/{@code externalStatusAtAddTime} — no
 * adapter yet. {@code modifiesStock}/{@code modifiesStockReason} removed in M1 (GLPI-adapter
 * strip) along with the whole local {@code MODEL_STOCK} counter/exception flow they gated.
 */
public record NoteItemRequest(
        @NotBlank String kind, // "ASSET" | "COUNTABLE"
        @NotNull Integer typeId,
        @NotNull Integer brandId,
        @NotNull Integer modelId,
        String serialNumber,
        String af,
        Integer quantity,
        String observations) {

    public boolean isAsset() {
        return "ASSET".equalsIgnoreCase(kind);
    }
}
