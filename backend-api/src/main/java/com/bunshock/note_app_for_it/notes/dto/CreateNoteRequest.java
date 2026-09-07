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
 * Sede). v1 scope: Entrega/Devolución/Entrega Permanente/Préstamo/Entrega-Proveedor only — Remito
 * de Envío is not yet ported (needs the Sede/SEDE_SHIPPING_INFO catalog module first).
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
        @NotEmpty List<@Valid NoteItemRequest> items) {

    public boolean isProviderNote() {
        return providerId != null;
    }
}
