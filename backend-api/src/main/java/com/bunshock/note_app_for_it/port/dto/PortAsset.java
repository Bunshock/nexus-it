package com.bunshock.note_app_for_it.port.dto;

/**
 * A single backend inventory record. {@code holderUsername} is the assigned user (GLPI
 * {@code users_id}); {@code availability} is derived from the backend state (E1a). {@code af} is
 * the inventory number (GLPI {@code otherserial}, read-only for the app).
 */
public record PortAsset(
        String id,
        String backend,
        String serial,
        String af,
        String name,
        String typeId,
        String brandId,
        String modelId,
        Availability availability,
        String holderUsername,
        String sedeId) {
}
