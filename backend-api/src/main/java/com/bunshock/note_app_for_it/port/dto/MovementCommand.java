package com.bunshock.note_app_for_it.port.dto;

/**
 * One item-level write against the backend, issued by the §10 write queue after a note is
 * approved + a sync/return/lost action fires. {@code holderUsername} null = clear the holder
 * (returns). {@code sedeId} non-null only for RELOCATE. {@code reason} + {@code actingUsername}
 * are stamped into the backend's movement-reason field (GLPI plugin Fields container id 8).
 */
public record MovementCommand(
        String assetId,
        String backend,
        MovementKind kind,
        String holderUsername,
        String sedeId,
        String reason,
        String actingUsername) {
}
