package com.bunshock.note_app_for_it.notes.dto;

import java.util.List;

/**
 * §5.2 {@code GET /notes/{id}} shape — mirrors the desktop app's {@code getById()}.
 * {@code stockApplied} and the destination label/address/recipients trio removed in M1
 * (GLPI-adapter strip, along with MODEL_STOCK/SEDE_SHIPPING_INFO/NOTE_REMITO_OTHER) —
 * {@code destinationSedeId}/{@code destinationSedeName} are all a Remito destination carries now.
 */
public record NoteDetailResponse(
        int id, String createdAt, String profileType, String approvalStatus, String rejectionReason,
        String authorName, String authorDni, String observations, String sede, int sedeId,
        String userName, String userDni, String userEmail, String motivo,
        String failureCause, String failureDetails, String areaEvento,
        String providerName, Integer providerId, String cuit, String responsibleName, String responsibleDni,
        Integer destinationSedeId, String destinationSedeName,
        List<NoteItemResponse> items) {
}
