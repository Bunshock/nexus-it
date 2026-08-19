package com.bunshock.note_app_for_it_frontend.services.history;

public interface IGLPIService {

    boolean isReachable();

    void syncNote(int noteReportId);

    boolean assetExistsInGlpi(String type, String brand, String model, String serial);

    String getAssignedUserInGlpi(String serial);
}
