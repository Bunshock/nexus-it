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
    private int providerId;
    private String providerName;
    private String cuit;
    private String motivo;
    private String failureCause;
    private String failureDetails;
    private String responsibleName;
    private String responsibleDni;
    private String areaEvento;
    private String observations;
    private String sede;
    private int sedeId;
    private String approvalStatus = "PENDING";
    private String rejectionReason;
    private Integer destinationSedeId;
    // Captured at Sede-selection time (RemitoNoteController.fillDestinationFields()), not
    // re-derived at save time — same "ids captured at ComboBox-selection time, not re-derived by
    // name later" precedent as NOTE_ITEM's catalog ids, since the active SEDE_SHIPPING_INFO row
    // for a Sede could in theory change between selection and save on a shared remote database.
    // Null for a custom/manual destination (no SEDE_SHIPPING_INFO row to reference at all).
    private Integer shippingInfoId;
    private String destinationLabel;
    private String address;
    private String recipients;
    private boolean stockApplied;
    private List<NoteReportItem> items;

    // Populated from JOIN queries — not stored directly in NOTE_REPORT
    private String authorName;
    private String authorDni;
    private String recipientDisplay;
    private int assetItemCount;
    private int countableItemCount;
    private int pendingItemCount;
    private int syncedItemCount;
    private int rejectedItemCount;
    private int returnPendingItemCount;
    private int returnedItemCount;
    private int lostItemCount;

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

    public int getProviderId() { return providerId; }
    public void setProviderId(int providerId) { this.providerId = providerId; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getCuit() { return cuit; }
    public void setCuit(String cuit) { this.cuit = cuit; }

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }

    public String getFailureCause() { return failureCause; }
    public void setFailureCause(String failureCause) { this.failureCause = failureCause; }

    public String getFailureDetails() { return failureDetails; }
    public void setFailureDetails(String failureDetails) { this.failureDetails = failureDetails; }

    public String getResponsibleName() { return responsibleName; }
    public void setResponsibleName(String responsibleName) { this.responsibleName = responsibleName; }

    public String getResponsibleDni() { return responsibleDni; }
    public void setResponsibleDni(String responsibleDni) { this.responsibleDni = responsibleDni; }

    public String getAreaEvento() { return areaEvento; }
    public void setAreaEvento(String areaEvento) { this.areaEvento = areaEvento; }

    public String getObservations() { return observations; }
    public void setObservations(String observations) { this.observations = observations; }

    public String getSede() { return sede; }
    public void setSede(String sede) { this.sede = sede; }

    public int getSedeId() { return sedeId; }
    public void setSedeId(int sedeId) { this.sedeId = sedeId; }

    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Integer getDestinationSedeId() { return destinationSedeId; }
    public void setDestinationSedeId(Integer destinationSedeId) { this.destinationSedeId = destinationSedeId; }

    public Integer getShippingInfoId() { return shippingInfoId; }
    public void setShippingInfoId(Integer shippingInfoId) { this.shippingInfoId = shippingInfoId; }

    public String getDestinationLabel() { return destinationLabel; }
    public void setDestinationLabel(String destinationLabel) { this.destinationLabel = destinationLabel; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getRecipients() { return recipients; }
    public void setRecipients(String recipients) { this.recipients = recipients; }

    public boolean isStockApplied() { return stockApplied; }
    public void setStockApplied(boolean stockApplied) { this.stockApplied = stockApplied; }

    public List<NoteReportItem> getItems() { return items; }
    public void setItems(List<NoteReportItem> items) { this.items = items; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getAuthorDni() { return authorDni; }
    public void setAuthorDni(String authorDni) { this.authorDni = authorDni; }

    public String getRecipientDisplay() { return recipientDisplay; }
    public void setRecipientDisplay(String recipientDisplay) { this.recipientDisplay = recipientDisplay; }

    public int getAssetItemCount() { return assetItemCount; }
    public void setAssetItemCount(int assetItemCount) { this.assetItemCount = assetItemCount; }

    public int getCountableItemCount() { return countableItemCount; }
    public void setCountableItemCount(int countableItemCount) { this.countableItemCount = countableItemCount; }

    public int getPendingItemCount() { return pendingItemCount; }
    public void setPendingItemCount(int pendingItemCount) { this.pendingItemCount = pendingItemCount; }

    public int getSyncedItemCount() { return syncedItemCount; }
    public void setSyncedItemCount(int syncedItemCount) { this.syncedItemCount = syncedItemCount; }

    public int getRejectedItemCount() { return rejectedItemCount; }
    public void setRejectedItemCount(int rejectedItemCount) { this.rejectedItemCount = rejectedItemCount; }

    public int getReturnPendingItemCount() { return returnPendingItemCount; }
    public void setReturnPendingItemCount(int returnPendingItemCount) { this.returnPendingItemCount = returnPendingItemCount; }

    public int getReturnedItemCount() { return returnedItemCount; }
    public void setReturnedItemCount(int returnedItemCount) { this.returnedItemCount = returnedItemCount; }

    public int getLostItemCount() { return lostItemCount; }
    public void setLostItemCount(int lostItemCount) { this.lostItemCount = lostItemCount; }
}
