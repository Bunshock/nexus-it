package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.util.function.Consumer;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.utils.ViewFactory;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Callback;

public class NoteGeneratorController {

    // Factory to load views for different profiles
    private ViewFactory viewFactory;

    // Profile selection buttons
    @FXML private ToggleButton btnUserNote, btnProviderNote;
    @FXML private ToggleGroup typeGroup;
    
    // Specific note profile content area
    @FXML private StackPane dynamicContentArea;

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

    // Observations field common to all profiles
    @FXML private TextField txtObservations;

    public void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
        // Default profile selection: user note
        showUserNoteView();
    }

    public void initialize() {
        btnUserNote.setOnAction(e -> showUserNoteView());
        btnProviderNote.setOnAction(e -> showProviderNoteView());

        // Ensure one profile is always selected
        typeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                oldToggle.setSelected(true);
            }
        });

        // Set up empty tables
        setupAssetTable();
        setupCountableTable();

        // Set up action columns
        colAssetActions.setCellFactory(createActionCellFactory(
            this::handleEditAsset,
            asset -> assetList.remove(asset)
        ));

        colCountActions.setCellFactory(createActionCellFactory(
            this::handleEditCountable, 
            countable -> countableList.remove(countable)
        ));

        // Populate with mock data for testing
        populateMockTableData();
    }

    private void showUserNoteView() {
        dynamicContentArea.getChildren().setAll(viewFactory.getUserNoteView());
    }

    private void showProviderNoteView() {
        dynamicContentArea.getChildren().setAll(viewFactory.getProviderNoteView());
    }

    // Generalized cell factory method for action columns
    private <T> Callback<TableColumn<T, Void>, TableCell<T, Void>> createActionCellFactory(
            Consumer<T> editAction, 
            Consumer<T> deleteAction) {
        
        return param -> new TableCell<T, Void>() {
            private final Button btnEdit = new Button("🖉");
            private final Button btnDelete = new Button("❌");
            private final HBox container = new HBox(btnEdit, btnDelete);

            {
                btnEdit.getStyleClass().add("button-icon-edit");
                btnDelete.getStyleClass().add("button-icon-delete");
                container.getStyleClass().add("action-container");

                btnEdit.setOnAction(event -> {
                    T item = getTableView().getItems().get(getIndex());
                    if (item != null) editAction.accept(item);
                });

                btnDelete.setOnAction(event -> {
                    T item = getTableView().getItems().get(getIndex());
                    if (item != null) deleteAction.accept(item);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    setGraphic(container);
                }
            }
        };
    }

    // TODO: Generalize handleEdit methods
    private void handleEditAsset(AssetItem asset) {
        System.out.println("Editing asset: " + asset.getModel());
        // TODO: Call item dialog pre-filled with item data for editing
    }

    private void handleEditCountable(CountableItem countable) {
        System.out.println("Editing countable: " + countable.getType());
        // TODO: Call item dialog pre-filled with item data for editing
    }

    // Mock equipment tables for testing UI
    private void populateMockTableData() {
        // Mock Assets (The top table)
        assetList.addAll(
            new AssetItem("Notebook", "MP", "PB G9", "Cargador original, Mouse USB", "SN123456789", "AF789012"),
            new AssetItem("Monitor", "Pamsung", "F24T35", "Cable HDMI, Cable de poder", "SN111111111", "AF345678")
        );

        // Mock Countables (The bottom table)
        countableList.addAll(
            new CountableItem("Cable UTP 2mts", "Generic", "CAT6", 1, "Gris"),
            new CountableItem("Adaptador HDMI a VGA", "Marca inventada", "Modelo inv.", 10, "Blanco"),
            new CountableItem("Mouse Pad", "Generic", "Standard", 1, "Negro - Siglo 21")
        );
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
        colAssetModel.setCellValueFactory(d -> d.getValue().getModel());
        colAssetSerial.setCellValueFactory(d -> d.getValue().getSerial());
        colAssetAF.setCellValueFactory(d -> d.getValue().getAf());
        colAssetObs.setCellValueFactory(d -> d.getValue().getObservations());
        tblAssets.setItems(assetList);
        // setupActionsColumn(colAssetActions);
    }

    private void setupCountableTable() {
        colCountType.setCellValueFactory(d -> d.getValue().getType());
        colCountBrand.setCellValueFactory(d -> d.getValue().getBrand());
        colCountModel.setCellValueFactory(d -> d.getValue().getModel());
        colCountQty.setCellValueFactory(d -> d.getValue().getQuantity().asObject());
        colCountObs.setCellValueFactory(d -> d.getValue().getObservations());
        tblCountables.setItems(countableList);
        // setupActionsColumn(colCountActions);
    }

    // Methods for the ItemDialogView to call
    public void addAsset(AssetItem item) { assetList.add(item); }
    public void addCountable(CountableItem item) { countableList.add(item); }

    @FXML
    private void handleClearForm() {
        txtObservations.clear();
        assetList.clear();
        countableList.clear();

        // Clear fields in the currently loaded profile view
        if (viewFactory.getUserNoteView() != null) {
            viewFactory.getUserNoteController().clearAllFields();
        }

        if (viewFactory.getProviderNoteView() != null) {
            viewFactory.getProviderNoteController().clearAllFields();
        }
    }

    @FXML
    private void handleGenerateNote() {
        System.out.println("Generando nota ...");
    }

}