package com.bunshock.note_app_for_it.notes.dto;

import jakarta.validation.constraints.NotBlank;

/** §4.4 — {@code quantity} only meaningful (and required) for a countable's partial loss; omit for a whole item. */
public record LostActionRequest(@NotBlank String reason, Integer quantity) {
}
