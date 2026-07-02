package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.NoteReport;

public class CachingHistoryService implements IHistoryService {

    private final IHistoryService primary;
    private final IHistoryService local;

    public CachingHistoryService(IHistoryService primary, IHistoryService local) {
        this.primary = primary;
        this.local = local;
    }

    @Override
    public int save(NoteReport report) {
        int id = primary.save(report);
        try { local.save(report); } catch (Exception ignored) {}
        return id;
    }

    @Override
    public List<NoteReport> getAll() {
        try { return primary.getAll(); } catch (Exception e) { return local.getAll(); }
    }

    @Override
    public NoteReport getById(int id) {
        try { return primary.getById(id); } catch (Exception e) { return local.getById(id); }
    }

    @Override
    public void markGlpiSynced(int reportId) {
        primary.markGlpiSynced(reportId);
        try { local.markGlpiSynced(reportId); } catch (Exception ignored) {}
    }
}
