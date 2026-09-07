package com.bunshock.note_app_for_it.port.dto;

/** Asset search criteria — any field null = not filtered. AND-combined. {@code serialContains} is a partial match (GLPI {@code searchtype=contains}). */
public record AssetQuery(String serialContains, String nameContains, String holderUsername, String backend) {
}
