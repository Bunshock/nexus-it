package com.bunshock.note_app_for_it_frontend.models;

import java.time.LocalDateTime;
import java.util.List;

public class NoteReport {
    private int id;
    private LocalDateTime createdAt;
    private String profileType;
    private boolean glpiSynced;
    private String userName;
    private String userDni;
    private String userEmail;
    private String providerName;
    private String cuit;
    private String motivo;
    private List<NoteReportItem> items;

    public NoteReport() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getProfileType() { return profileType; }
    public void setProfileType(String profileType) { this.profileType = profileType; }

    public boolean isGlpiSynced() { return glpiSynced; }
    public void setGlpiSynced(boolean glpiSynced) { this.glpiSynced = glpiSynced; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getUserDni() { return userDni; }
    public void setUserDni(String userDni) { this.userDni = userDni; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getCuit() { return cuit; }
    public void setCuit(String cuit) { this.cuit = cuit; }

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }

    public List<NoteReportItem> getItems() { return items; }
    public void setItems(List<NoteReportItem> items) { this.items = items; }
}
