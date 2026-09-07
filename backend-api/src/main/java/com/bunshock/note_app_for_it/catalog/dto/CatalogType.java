package com.bunshock.note_app_for_it.catalog.dto;

/** §3.1 — {@code GET /catalog/types} row shape. */
public record CatalogType(int id, String name, boolean isAsset, boolean requiresSerial) {
}
