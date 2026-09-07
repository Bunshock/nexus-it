package com.bunshock.note_app_for_it.port.dto;

/**
 * Generic availability vocabulary — the {@code port/} layer never speaks GLPI's {@code states_id}.
 * The adapter maps every backend state into one of these (E1a): only {@code AVAILABLE} can be
 * handed out or counted as stock; {@code IN_USE} is held by someone; {@code UNAVAILABLE} covers
 * in-transit / repair / warranty-claim / decommissioned / unknown.
 */
public enum Availability {
    AVAILABLE,
    IN_USE,
    UNAVAILABLE
}
