package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.services.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;

public class DatabaseSectionController {

    @FXML private TextField txtDbUrl;
    @FXML private Label lblConnectionStatus;
    @FXML private ListView<EquipmentType> listTypes;
    @FXML private ListView<EquipmentBrand> listBrands;
    @FXML private ListView<EquipmentModel> listModels;

    private IEquipmentService equipmentService;

    public void initialize() {
        equipmentService = ServiceLocator.getInstance().getEquipmentService();
        refreshLists();

        listTypes.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) refreshBrandsForType(sel.getId());
        });

        listBrands.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
            if (sel != null && type != null) refreshModelsForBrandType(sel.getId(), type.getId());
        });
    }

    private void refreshLists() {
        List<EquipmentType> types = equipmentService.getAllTypes();
        listTypes.setItems(FXCollections.observableArrayList(types));
        listBrands.setItems(FXCollections.observableArrayList());
        listModels.setItems(FXCollections.observableArrayList());
    }

    private void refreshBrandsForType(int typeId) {
        listBrands.setItems(FXCollections.observableArrayList(equipmentService.getBrandsForType(typeId)));
        listModels.setItems(FXCollections.observableArrayList());
    }

    private void refreshModelsForBrandType(int brandId, int typeId) {
        listModels.setItems(FXCollections.observableArrayList(
            equipmentService.getModelsForBrandAndType(brandId, typeId)));
    }

    @FXML
    private void handleConnect() {
        lblConnectionStatus.setStyle("-fx-text-fill: #f59e0b;");
        lblConnectionStatus.setText("Conexión remota aún no implementada");
    }

    @FXML
    private void handleAddType() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Agregar Tipo");
        dialog.setHeaderText("Nuevo tipo de equipo");
        dialog.setContentText("Nombre:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                equipmentService.addType(name.trim(), true);
                refreshLists();
            }
        });
    }

    @FXML
    private void handleRemoveType() {
        EquipmentType selected = listTypes.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try {
            equipmentService.removeType(selected.getId());
            refreshLists();
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    @FXML
    private void handleAddBrand() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Agregar Marca");
        dialog.setHeaderText("Nueva marca");
        dialog.setContentText("Nombre:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                equipmentService.addBrand(name.trim());
                EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
                if (type != null) refreshBrandsForType(type.getId());
            }
        });
    }

    @FXML
    private void handleRemoveBrand() {
        EquipmentBrand selected = listBrands.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try {
            equipmentService.removeBrand(selected.getId());
            EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
            if (type != null) refreshBrandsForType(type.getId());
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    @FXML
    private void handleAddModel() {
        EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
        EquipmentBrand brand = listBrands.getSelectionModel().getSelectedItem();
        if (type == null || brand == null) {
            showError("Seleccione un tipo y una marca primero");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Agregar Modelo");
        dialog.setHeaderText("Nuevo modelo para " + brand.getName() + " — " + type.getName());
        dialog.setContentText("Nombre:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                equipmentService.addModel(name.trim(), brand.getId(), type.getId());
                refreshModelsForBrandType(brand.getId(), type.getId());
            }
        });
    }

    @FXML
    private void handleRemoveModel() {
        EquipmentModel selected = listModels.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try {
            equipmentService.removeModel(selected.getId());
            EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
            EquipmentBrand brand = listBrands.getSelectionModel().getSelectedItem();
            if (type != null && brand != null)
                refreshModelsForBrandType(brand.getId(), type.getId());
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
