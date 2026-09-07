package com.bunshock.note_app_for_it.config.dto;

import java.util.List;

/** The 4 Motivo dropdown categories — mirrors app-config.json's {@code motivoOptions} object. */
public record MotivoOptions(
        List<String> entrega,
        List<String> finDeContrato,
        List<String> proveedor,
        List<String> devolucion) {
}
