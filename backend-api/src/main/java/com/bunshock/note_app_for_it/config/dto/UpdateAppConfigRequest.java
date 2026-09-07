package com.bunshock.note_app_for_it.config.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * PUT /config body — replaces the whole document (real REST PUT semantics), except
 * {@code smtpPassword}: null or blank means "leave the stored value unchanged" (write-only field
 * convention already used throughout the desktop app for secrets — e.g. the remote-DB password
 * field). {@code genericLabel} IS settable here (renames the actual "Genérico / Otro" BRAND row
 * the next time it's touched — see ConfigRepository); {@code genericBrandId}/{@code genericModelId}
 * are NOT settable — they're derived, not stored.
 */
public record UpdateAppConfigRequest(
        boolean afEnabled,
        @NotBlank String afPrefix,
        @NotBlank String afSeparator,
        String smtpHost,
        Integer smtpPort,
        String smtpSenderAddress,
        String smtpPassword,
        @Min(1) int noteItemLimit,
        @NotBlank String failureTriggerMotivo,
        @NotBlank String genericLabel,
        @NotNull List<String> returnableMotivosProveedor,
        @NotNull @Valid MotivoOptions motivoOptions,
        @NotNull List<String> fallaOptions) {
}
