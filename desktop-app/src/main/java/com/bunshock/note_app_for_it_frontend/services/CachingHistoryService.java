package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.ReturnStatus;

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
    public List<NoteReport> getFiltered(HistoryFilter filter) {
        try { return primary.getFiltered(filter); } catch (Exception e) { return local.getFiltered(filter); }
    }

    @Override
    public List<NoteReport> getPendingGlpiSync() {
        try { return primary.getPendingGlpiSync(); } catch (Exception e) { return local.getPendingGlpiSync(); }
    }

    @Override
    public NoteReport getById(int id) {
        try { return primary.getById(id); } catch (Exception e) { return local.getById(id); }
    }

    @Override
    public void updateItemGlpiStatus(int itemId, GlpiStatus status, String reason) {
        primary.updateItemGlpiStatus(itemId, status, reason);
        try { local.updateItemGlpiStatus(itemId, status, reason); } catch (Exception ignored) {}
    }

    @Override
    public void updateItemReturnStatus(int itemId, ReturnStatus status, String reason) {
        primary.updateItemReturnStatus(itemId, status, reason);
        try { local.updateItemReturnStatus(itemId, status, reason); } catch (Exception ignored) {}
    }

    @Override
    public List<String> getDistinctItemTypes() {
        try { return primary.getDistinctItemTypes(); } catch (Exception e) { return local.getDistinctItemTypes(); }
    }

    @Override
    public List<String> getDistinctSedes() {
        try { return primary.getDistinctSedes(); } catch (Exception e) { return local.getDistinctSedes(); }
    }

    @Override
    public List<String> getDistinctItemBrands(List<String> types) {
        try { return primary.getDistinctItemBrands(types); } catch (Exception e) { return local.getDistinctItemBrands(types); }
    }

    @Override
    public List<String> getDistinctItemModels(List<String> types, List<String> brands) {
        try { return primary.getDistinctItemModels(types, brands); } catch (Exception e) { return local.getDistinctItemModels(types, brands); }
    }

    @Override
    public List<String> getMostUsedTypeNames(int windowDays, int minUses, int limit) {
        try { return primary.getMostUsedTypeNames(windowDays, minUses, limit); }
        catch (Exception e) { return local.getMostUsedTypeNames(windowDays, minUses, limit); }
    }

    @Override
    public List<String> getMostUsedBrandNames(String typeName, int windowDays, int minUses, int limit) {
        try { return primary.getMostUsedBrandNames(typeName, windowDays, minUses, limit); }
        catch (Exception e) { return local.getMostUsedBrandNames(typeName, windowDays, minUses, limit); }
    }

    @Override
    public List<String> getMostUsedModelNames(String typeName, String brandName, int windowDays, int minUses, int limit) {
        try { return primary.getMostUsedModelNames(typeName, brandName, windowDays, minUses, limit); }
        catch (Exception e) { return local.getMostUsedModelNames(typeName, brandName, windowDays, minUses, limit); }
    }

}
