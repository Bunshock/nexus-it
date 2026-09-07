package com.bunshock.note_app_for_it.notes.dto;

import jakarta.validation.constraints.NotBlank;

/** Mandatory-reason body — reject-note, reject-sync, lost, and the "no modifica stock" exception all use this shape. */
public record ReasonRequest(@NotBlank String reason) {
}
