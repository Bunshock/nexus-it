package com.bunshock.note_app_for_it.notes;

import java.time.LocalDate;
import java.util.List;

/** §5.2 query params — v1 subset (no itemTypes/brands/models or syncStatuses/returnStatuses filters yet). */
public record NotesFilter(
        List<String> profileTypes,
        List<String> approvalStatuses,
        List<Integer> sedeIds,
        String authorSearch,
        String recipientSearch,
        LocalDate dateFrom,
        LocalDate dateTo) {

    public static NotesFilter empty() {
        return new NotesFilter(null, null, null, null, null, null, null);
    }
}
