package com.bunshock.note_app_for_it.config.dto;

import java.util.List;

/**
 * §8 shape. SMTP fields removed in M1 (GLPI-adapter strip — email notifications are out of scope
 * for this middleware). {@code genericBrandId}/{@code genericModelId} are resolved live from the
 * catalog (the actual "Genérico / Otro" BRAND/MODEL rows), not stored config — these ARE the real
 * internal ids for now (M1 keeps the local catalog tables around, see CatalogRepository),
 * stringified to match the contract's "opaque string id" shape; M3 may change what they resolve
 * against once GLPI backs the catalog.
 */
public record AppConfigResponse(
        boolean afEnabled,
        String afPrefix,
        String afSeparator,
        int noteItemLimit,
        String failureTriggerMotivo,
        String genericLabel,
        String genericBrandId,
        String genericModelId,
        List<String> returnableMotivosProveedor,
        MotivoOptions motivoOptions,
        List<String> fallaOptions) {
}
