package com.bunshock.note_app_for_it.notes.dto;

import java.util.List;

/** §5.2 {@code GET /notes/{id}} shape — mirrors the desktop app's {@code getById()}. */
public record NoteDetailResponse(
        int id, String createdAt, String profileType, String approvalStatus, String rejectionReason,
        String authorName, String authorDni, String observations, String sede, int sedeId,
        String userName, String userDni, String userEmail, String motivo,
        String failureCause, String failureDetails, String areaEvento,
        String providerName, Integer providerId, String cuit, String responsibleName, String responsibleDni,
        boolean stockApplied,
        List<NoteItemResponse> items) {
}
