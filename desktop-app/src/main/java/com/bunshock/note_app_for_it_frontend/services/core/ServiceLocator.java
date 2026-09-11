package com.bunshock.note_app_for_it_frontend.services.core;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;

import com.bunshock.note_app_for_it_frontend.services.auth.IADService;
import com.bunshock.note_app_for_it_frontend.services.auth.RestDirectoryService;
import com.bunshock.note_app_for_it_frontend.services.catalog.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.catalog.RestEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.history.GLPIServiceStub;
import com.bunshock.note_app_for_it_frontend.services.history.IGLPIService;
import com.bunshock.note_app_for_it_frontend.services.history.IHistoryService;
import com.bunshock.note_app_for_it_frontend.services.history.RestHistoryService;
import com.bunshock.note_app_for_it_frontend.services.update.IUpdateService;
import com.bunshock.note_app_for_it_frontend.services.update.NetworkShareUpdateService;

public class ServiceLocator {

    private static ServiceLocator instance;

    private volatile IEquipmentService equipmentService;
    private IADService adService;
    private IGLPIService glpiService;
    private volatile IHistoryService historyService;
    private IUpdateService updateService;

    private ServiceLocator() {}

    public static ServiceLocator getInstance() {
        if (instance == null) {
            instance = new ServiceLocator();
        }
        return instance;
    }

    public void initialize(AppConfig config) {
        MiddlewareClient.getInstance().configure(config.middleware != null ? config.middleware.baseUrl : null);

        equipmentService = new RestEquipmentService(MiddlewareClient.getInstance());
        historyService   = new RestHistoryService(MiddlewareClient.getInstance());

        // Phase B: directory search (recipient lookup) goes through the middleware now — the
        // middleware owns its own directory config server-side, nothing left to configure here.
        adService = new RestDirectoryService(MiddlewareClient.getInstance());

        glpiService  = new GLPIServiceStub();

        NetworkShareUpdateService realUpdate = NetworkShareUpdateService.getInstance();
        realUpdate.configure(config.updates != null ? config.updates.manifestPath : null);
        updateService = realUpdate;
    }

    public IEquipmentService getEquipmentService() { return equipmentService; }
    public IADService        getAdService()         { return adService; }
    public IGLPIService      getGlpiService()       { return glpiService; }
    public IHistoryService   getHistoryService()    { return historyService; }
    public IUpdateService    getUpdateService()     { return updateService; }

    public void setEquipmentService(IEquipmentService s) { equipmentService = s; }
    public void setAdService(IADService s)               { adService = s; }
    public void setHistoryService(IHistoryService s)     { historyService = s; }
    public void setUpdateService(IUpdateService s)       { updateService = s; }
}
