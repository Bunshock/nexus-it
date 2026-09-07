package com.bunshock.note_app_for_it.catalog.dto;

/** {@code brandTypeId == null} marks the single global "Genérico / Otro" model row. */
public record CatalogModel(int id, Integer brandTypeId, String name) {
}
