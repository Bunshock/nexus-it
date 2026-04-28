package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class UserNoteController {

    // Note type selection
    @FXML private ToggleGroup typeGroup;
    @FXML private ToggleButton btnTypeEntrega;
    @FXML private ToggleButton btnTypeDevolucion;
    @FXML private ToggleButton btnTypePrestamo;
    @FXML private ToggleButton btnTypeFinContrato;

    // User info fields
    @FXML private TextField txtUserDni;
    @FXML private TextField txtUserName;
    @FXML private TextField txtUserEmail;

    // Equipment tables
    // Assets Table (S/N)
    @FXML private TableView<AssetItem> tblAssets;
    @FXML private TableColumn<AssetItem, String> colAssetType, colAssetBrand, colAssetModel, colAssetSerial, colAssetAF;
    @FXML private TableColumn<AssetItem, String> colAssetObs;
    @FXML private TableColumn<AssetItem, Void> colAssetActions;

    // Countables Table (Quantity)
    @FXML private TableView<CountableItem> tblCountables;
    @FXML private TableColumn<CountableItem, String> colCountType, colCountBrand, colCountModel;
    @FXML private TableColumn<CountableItem, Integer> colCountQty;
    @FXML private TableColumn<CountableItem, String> colCountObs;
    @FXML private TableColumn<CountableItem, Void> colCountActions;

    private ObservableList<AssetItem> assetList = FXCollections.observableArrayList();
    private ObservableList<CountableItem> countableList = FXCollections.observableArrayList();

    public void initialize() {
        // Setup note type toggle group
        typeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle != null) {
                ToggleButton selectedBtn = (ToggleButton) newToggle;
                String selectedType = selectedBtn.getText();
                System.out.println("Tipo de nota seleccionado: " + selectedType);
            }
        });

        btnTypeEntrega.setSelected(true);

        // Set up empty tables
        setupAssetTable();
        setupCountableTable();
        
        // TODO: AD lookup for user data. Use listener or a "Buscar en AD" button
    }

    @FXML
    private void handleAddItem() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/bunshock/note_app_for_it_frontend/views/ItemDialogView.fxml"));
            Parent root = loader.load();

            ItemDialogController controller = loader.getController();
            controller.setParentController(this); 

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setTitle("Agregar Equipamiento");
            stage.setScene(new Scene(root));
            stage.showAndWait();
        } catch (IOException e) {
            System.err.println("Error loading ItemDialogView: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void setupAssetTable() {
        colAssetType.setCellValueFactory(d -> d.getValue().getType());
        colAssetBrand.setCellValueFactory(d -> d.getValue().getBrand());
        colAssetSerial.setCellValueFactory(d -> d.getValue().getSerial());
        colAssetAF.setCellValueFactory(d -> d.getValue().getAf());
        tblAssets.setItems(assetList);
        // setupActionsColumn(colAssetActions);
    }

    private void setupCountableTable() {
        colCountType.setCellValueFactory(d -> d.getValue().getType());
        colCountBrand.setCellValueFactory(d -> d.getValue().getBrand());
        colCountQty.setCellValueFactory(d -> d.getValue().getQuantity().asObject());
        tblCountables.setItems(countableList);
        // setupActionsColumn(colCountActions);
    }

    // Methods for the ItemDialogView to call
    public void addAsset(AssetItem item) { assetList.add(item); }
    public void addCountable(CountableItem item) { countableList.add(item); }

}