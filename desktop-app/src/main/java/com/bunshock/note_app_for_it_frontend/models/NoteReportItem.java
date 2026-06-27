package com.bunshock.note_app_for_it_frontend.models;

public class NoteReportItem {
    private String typeName;
    private String brandName;
    private String modelName;
    private String serialNumber;
    private String af;
    private int quantity;
    private String observations;

    public NoteReportItem() {}

    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }

    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getAf() { return af; }
    public void setAf(String af) { this.af = af; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public String getObservations() { return observations; }
    public void setObservations(String observations) { this.observations = observations; }
}
