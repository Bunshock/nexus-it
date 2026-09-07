package com.bunshock.note_app_for_it.notes.dto;

/** §4.3 — {@code quantity} only meaningful (and required) for a countable's partial return; omit for a whole item. */
public record ReturnActionRequest(Integer quantity) {
}
