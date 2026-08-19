package com.bunshock.note_app_for_it_frontend.services;

import java.util.Collections;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.history.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnStatus;

public interface IHistoryService {

    int save(NoteReport report);

    List<NoteReport> getAll();

    NoteReport getById(int id);

    default List<NoteReport> getFiltered(HistoryFilter filter) { return getAll(); }

    default List<NoteReport> getPendingGlpiSync() { return Collections.emptyList(); }

    default void updateItemGlpiStatus(int itemId, GlpiStatus status, String reason) {}

    /**
     * Second, independent GLPI dimension — "synced back into GLPI" — only ever applicable to a
     * returnable Provider note's asset item, and only once its return has actually been validated
     * (see NOTE_ITEM_GLPI_RETURN_TRACKING's own doc in DatabaseService for why this can't just
     * reuse updateItemGlpiStatus() above).
     */
    default void updateItemGlpiReturnStatus(int itemId, GlpiStatus status, String reason) {}

    default void updateItemReturnStatus(int itemId, ReturnStatus status, String reason) {}

    /**
     * Records a partial return/lost quantity for a countable item (status must be RETURNED or
     * LOST — PENDING/N_A are rejected). Append-only: each call adds a new allocation on top of
     * whatever was already recorded for this item, rather than replacing it — see
     * NOTE_ITEM_RETURN_ALLOCATION's own doc in DatabaseService for why. Never used for asset
     * items, which stay on the whole-item updateItemReturnStatus() above.
     */
    default void allocateCountableReturn(int itemId, ReturnStatus status, int quantity, String reason) {}

    default List<NoteReport> getPendingApproval() { return Collections.emptyList(); }

    default void updateNoteApprovalStatus(int reportId, String status, String rejectionReason) {}

    default List<String> getDistinctItemTypes() { return Collections.emptyList(); }

    default List<String> getDistinctItemBrands(List<String> types) { return Collections.emptyList(); }

    default List<String> getDistinctItemModels(List<String> types, List<String> brands) { return Collections.emptyList(); }

    default List<String> getMostUsedTypeNames(int windowDays, int minUses, int limit) { return Collections.emptyList(); }

    default List<String> getMostUsedBrandNames(String typeName, int windowDays, int minUses, int limit) { return Collections.emptyList(); }

    default List<String> getMostUsedModelNames(String typeName, String brandName, int windowDays, int minUses, int limit) { return Collections.emptyList(); }
}
