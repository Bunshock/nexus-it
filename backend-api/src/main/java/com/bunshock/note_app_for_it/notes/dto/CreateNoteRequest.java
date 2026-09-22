package com.bunshock.note_app_for_it.notes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * §5.1 — mirrors the desktop app's {@code NoteReport}/{@code NoteReportItem} shape. Technician
 * identity is deliberately NOT here (§2.5) — the caller's session supplies it. Sede is ALSO not
 * here, on purpose (a deviation from the literal contract JSON): in the desktop app today, Sede
 * was never a client-chosen value — every note is always stamped with the creating technician's
 * own assigned {@code APP_USER.sede_id}, mandatory, never picked. Resolving it server-side here
 * matches that real behavior and is also safer (a client can't create a note under an arbitrary
 * Sede).
 *
 * <p>For a Remito de Envío the destination is a real client choice (unlike the technician's own
 * Sede): {@code destinationSedeId}, a real catalog {@code SEDE} id — collapsed from the old
 * shippingInfoId/free-text-destination pair in M1 (GLPI-adapter strip): a custom/manual
 * destination with no catalog row is no longer supported, since address/recipient info for a
 * Remito destination is expected to come from GLPI's {@code Location} entity once M3 lands, not a
 * local {@code SEDE_SHIPPING_INFO} table.
 */
public record CreateNoteRequest(
        @NotBlank String profileType,
        String userName,
        String userDni,
        String userEmail,
        String motivo, // or the tentative return date string, for Préstamo — same overload as today
        String areaEvento,
        String failureCause,
        String failureDetails,
        Integer providerId,
        String cuit,
        String responsibleName,
        String responsibleDni,
        String observations,
        Integer destinationSedeId,
        @NotEmpty List<@Valid NoteItemRequest> items) {

    public boolean isProviderNote() {
        return providerId != null;
    }
}
