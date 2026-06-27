package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.NoteReport;

public interface IHistoryService {

    int save(NoteReport report);

    List<NoteReport> getAll();

    NoteReport getById(int id);

    void markGlpiSynced(int reportId);
}
