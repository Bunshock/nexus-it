package com.bunshock.note_app_for_it.port.dto;

/**
 * The generic movement a synced note item performs on a backend record. The adapter translates
 * each to a concrete backend state via its {@code syncTargets} config (confirmed 2026-09-07:
 * ENTREGA/ENTREGA_PERMANENTE→31, PRESTAMO→34, DEVOLUCION/RETURN→33, LOST→45; RELOCATE keeps the
 * state and only changes the location).
 */
public enum MovementKind {
    ENTREGA,
    ENTREGA_PERMANENTE,
    PRESTAMO,
    DEVOLUCION,
    RETURN,
    LOST,
    RELOCATE
}
