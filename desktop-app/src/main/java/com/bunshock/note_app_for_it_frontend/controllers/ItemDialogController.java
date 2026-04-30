package com.bunshock.note_app_for_it_frontend.controllers;

import org.controlsfx.control.SearchableComboBox;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class ItemDialogController {
        
    // Toggle for equipment mode
    @FXML private ToggleButton btnModeAsset, btnModeCountable;
    
    // Common fields
    @FXML private SearchableComboBox<String> cmbType;
    @FXML private TextField txtBrand, txtModel, txtObs;

    // Mode-specific containers
    // Asset-specific fields
    @FXML private VBox containerAssetFields;
    @FXML private TextField txtSerial, txtAF;
    // Countable-specific fields
    @FXML private VBox containerCountableFields;
    @FXML private Spinner<Integer> spinQty;
    
    // Action buttons
    @FXML private Button btnAdd, btnCancel;

    private UserNoteController parentController;

    public void setParentController(UserNoteController parent) {
        this.parentController = parent;
    }

    @FXML
    private void handleModeChange() {
        boolean isAsset = btnModeAsset.isSelected();
        
        // Toggle Assets
        containerAssetFields.setVisible(isAsset);
        containerAssetFields.setManaged(isAsset);
        // TODO: Add a "Buscar en inventario" button for assets that opens a search dialog. The dialog
        // will allow searching for existing assets using S/N or A/F using GLPI or cached DB data
        
        // Toggle Countables
        containerCountableFields.setVisible(!isAsset);
        containerCountableFields.setManaged(!isAsset);
    }

    @FXML
    private void onSave() {
        if (btnModeAsset.isSelected()) {
            parentController.addAsset(new AssetItem(
                cmbType.getValue(), txtBrand.getText(), txtModel.getText(),
                txtObs.getText(), txtSerial.getText(), txtAF.getText()
            ));
        } else {
            parentController.addCountable(new CountableItem(
                cmbType.getValue(), txtBrand.getText(), txtModel.getText(),
                spinQty.getValue(), txtObs.getText()
            ));
        }
        ((Stage) btnAdd.getScene().getWindow()).close();
    }

    @FXML
    private void onCancel() {
        ((Stage) btnCancel.getScene().getWindow()).close();
    }
}