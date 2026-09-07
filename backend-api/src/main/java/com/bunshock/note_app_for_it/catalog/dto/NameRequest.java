package com.bunshock.note_app_for_it.catalog.dto;

import jakarta.validation.constraints.NotBlank;

/** Add/rename body shape shared by Type/Brand/Model — all three only ever take a bare name. */
public record NameRequest(@NotBlank String name) {
}
