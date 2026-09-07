package com.bunshock.note_app_for_it.port.dto;

/**
 * A catalog "type" as seen by {@code core/}. {@code id} is an opaque string (for GLPI, a composite
 * of itemtype + local id — N2). {@code backend} names the underlying itemtype (e.g. {@code Computer},
 * {@code Peripheral}, {@code Consumable}) so the adapter knows which GLPI resource + generic-id map
 * to use; {@code countable} is whether this type is stock-counted rather than serialized.
 */
public record PortType(String id, String backend, String name, boolean asset, boolean requiresSerial) {
}
