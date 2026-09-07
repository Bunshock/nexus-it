package com.bunshock.note_app_for_it.port.dto;

/** A plain {id, name} catalog reference — used for brands (GLPI Manufacturer), sedes (Location), and providers (Supplier). */
public record PortRef(String id, String name) {
}
