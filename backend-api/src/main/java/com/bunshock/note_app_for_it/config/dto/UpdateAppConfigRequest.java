package com.bunshock.note_app_for_it.config.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * PUT /config body — replaces the whole document (real REST PUT semantics). SMTP fields removed
 * in M1 (GLPI-adapter strip). {@code genericLabel} IS settable here (renames the actual
 * "Genérico / Otro" BRAND row the next time it's touched — see ConfigRepository);
 * {@code genericBrandId}/{@code genericModelId} are NOT settable — they're derived, not stored.
 * Now flat SUPERADMIN-gated (see ConfigController) rather than per-field-group.
 */
public record UpdateAppConfigRequest(
        boolean afEnabled,
        @NotBlank String afPrefix,
        @NotBlank String afSeparator,
        @Min(1) int noteItemLimit,
        @NotBlank String failureTriggerMotivo,
        @NotBlank String genericLabel,
        @NotNull List<String> returnableMotivosProveedor,
        @NotNull @Valid MotivoOptions motivoOptions,
        @NotNull List<String> fallaOptions) {
}
