package com.bunshock.note_app_for_it.config.dto;

import java.util.List;

/**
 * §8 shape. {@code smtpPassword} is deliberately absent — write-only via
 * {@link UpdateAppConfigRequest}, never returned (§9). {@code genericBrandId}/{@code genericModelId}
 * are resolved live from the catalog (the actual "Genérico / Otro" BRAND/MODEL rows), not stored
 * config — v1 has no external adapter, so these ARE the real internal ids, stringified to match
 * the contract's "opaque string id" shape.
 */
public record AppConfigResponse(
        boolean afEnabled,
        String afPrefix,
        String afSeparator,
        String smtpHost,
        Integer smtpPort,
        String smtpSenderAddress,
        int noteItemLimit,
        String failureTriggerMotivo,
        String genericLabel,
        String genericBrandId,
        String genericModelId,
        List<String> returnableMotivosProveedor,
        MotivoOptions motivoOptions,
        List<String> fallaOptions) {
}
