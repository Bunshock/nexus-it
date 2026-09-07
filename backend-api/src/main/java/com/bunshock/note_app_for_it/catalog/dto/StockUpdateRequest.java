package com.bunshock.note_app_for_it.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** {@code reason} mirrors the desktop app's mandatory "MOTIVO DEL CAMBIO" field on both Base de Datos stock dialogs. */
public record StockUpdateRequest(@NotNull @Min(0) Integer stock, @NotBlank String reason) {
}
