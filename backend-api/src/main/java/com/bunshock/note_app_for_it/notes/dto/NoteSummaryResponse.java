package com.bunshock.note_app_for_it.notes.dto;

/** §5.2 list-row shape — mirrors the desktop app's {@code mapSummary()}. */
public record NoteSummaryResponse(
        int id, String createdAt, String profileType, String approvalStatus, String rejectionReason,
        String authorName, String authorDni, String recipient, String motivo, String sede, Integer sedeId,
        int assetItemCount, int countableItemCount,
        int pendingItemCount, int syncedItemCount, int rejectedItemCount,
        int returnPendingItemCount, int returnedItemCount, int lostItemCount,
        String destinationLabel, String destinationAddress, String destinationRecipients) {
}
