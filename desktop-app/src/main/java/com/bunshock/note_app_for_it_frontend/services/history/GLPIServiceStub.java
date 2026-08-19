package com.bunshock.note_app_for_it_frontend.services.history;

public class GLPIServiceStub implements IGLPIService {

    @Override
    public boolean isReachable() {
        return false;
    }

    @Override
    public void syncNote(int noteReportId) {
        // TODO: implement GLPI sync when integration is in scope
    }

    @Override
    public boolean assetExistsInGlpi(String type, String brand, String model, String serial) {
        // TODO: implement real GLPI asset lookup via REST API
        return true;
    }

    @Override
    public String getAssignedUserInGlpi(String serial) {
        // TODO: implement real GLPI assignment lookup via REST API
        // Returns null to indicate no assignment conflict
        return null;
    }
}
