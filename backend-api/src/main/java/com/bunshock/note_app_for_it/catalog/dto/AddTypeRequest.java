package com.bunshock.note_app_for_it.catalog.dto;

import jakarta.validation.constraints.NotBlank;

public record AddTypeRequest(@NotBlank String name, boolean isAsset) {
}
