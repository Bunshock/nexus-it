package com.bunshock.note_app_for_it.port.dto;

/** A catalog model, scoped to its {@code typeId}. {@code generic} = this is the "Genérico / Otro" fallback row for that type. */
public record PortModel(String id, String name, String typeId, boolean generic) {
}
