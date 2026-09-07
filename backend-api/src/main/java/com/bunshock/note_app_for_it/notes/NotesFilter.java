package com.bunshock.note_app_for_it.notes;

import java.time.LocalDate;
import java.util.List;

/**
 * §5.2 / D7 query params. {@code itemTypes}/{@code itemBrands}/{@code itemModels} match by
 * catalog <em>name</em> (regardless of deprecated status, so a renamed-away name still finds the
 * notes that used it) and are ANDed within a single item. {@code syncStatuses} / {@code
 * returnStatuses} are per-note-aggregate ("any item PENDING", "nothing tracked = N_A") — computed
 * from the summary-row counts, not a SQL predicate, mirroring the desktop app's
 * {@code matchesGlpiStatus()}.
 */
public record NotesFilter(
        List<String> profileTypes,
        List<String> approvalStatuses,
        List<Integer> sedeIds,
        String authorSearch,
        String recipientSearch,
        LocalDate dateFrom,
        LocalDate dateTo,
        List<String> itemTypes,
        List<String> itemBrands,
        List<String> itemModels,
        List<String> syncStatuses,
        List<String> returnStatuses) {

    public static NotesFilter empty() {
        return new NotesFilter(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
