package com.bunshock.note_app_for_it_frontend.services;

import java.util.Collections;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;

public interface IHistoryService {

    int save(NoteReport report);

    List<NoteReport> getAll();

    NoteReport getById(int id);

    default List<NoteReport> getFiltered(HistoryFilter filter) { return getAll(); }

    default List<NoteReport> getPendingGlpiSync() { return Collections.emptyList(); }

    default void updateItemGlpiStatus(int itemId, GlpiStatus status, String reason) {}

    default List<String> getDistinctItemTypes() { return Collections.emptyList(); }

    default List<String> getDistinctItemBrands(List<String> types) { return Collections.emptyList(); }

    default List<String> getDistinctItemModels(List<String> types, List<String> brands) { return Collections.emptyList(); }
}
