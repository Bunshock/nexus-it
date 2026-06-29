package com.bunshock.note_app_for_it_frontend.models;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;

public class SnValidationRow {
    private final int modelId;
    private final SimpleStringProperty typeName = new SimpleStringProperty();
    private final SimpleStringProperty brandName = new SimpleStringProperty();
    private final SimpleStringProperty modelName = new SimpleStringProperty();
    private final SimpleStringProperty regex = new SimpleStringProperty();
    private final SimpleBooleanProperty active = new SimpleBooleanProperty();

    public SnValidationRow(int modelId, String typeName, String brandName, String modelName,
                           String regex, boolean active) {
        this.modelId = modelId;
        this.typeName.set(typeName);
        this.brandName.set(brandName);
        this.modelName.set(modelName);
        this.regex.set(regex != null ? regex : "");
        this.active.set(active);
    }

    public int getModelId()                          { return modelId; }
    public SimpleStringProperty typeNameProperty()   { return typeName; }
    public SimpleStringProperty brandNameProperty()  { return brandName; }
    public SimpleStringProperty modelNameProperty()  { return modelName; }
    public SimpleStringProperty regexProperty()      { return regex; }
    public SimpleBooleanProperty activeProperty()    { return active; }

    public String getTypeName()  { return typeName.get(); }
    public String getBrandName() { return brandName.get(); }
    public String getModelName() { return modelName.get(); }
    public String getRegex()     { return regex.get(); }
    public boolean isActive()    { return active.get(); }
}
