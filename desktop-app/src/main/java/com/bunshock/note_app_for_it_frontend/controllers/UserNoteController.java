package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.services.IADService;
import com.bunshock.note_app_for_it_frontend.services.MockADService;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Callback;
import javafx.util.Duration;

public class UserNoteController {

    // Note type selection
    @FXML private ToggleGroup typeGroup;
    @FXML private ToggleButton btnTypeEntrega;
    @FXML private ToggleButton btnTypeDevolucion;
    @FXML private ToggleButton btnTypePrestamo;
    @FXML private ToggleButton btnTypeFinContrato;

    // AD search fields and status
    @FXML private Label lblADStatus;

    // User info fields
    @FXML private TextField txtUserDni;
    @FXML private TextField txtUserName;
    @FXML private TextField txtUserAccount;
    @FXML private Label lblUserEmail;

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

    // Mock AD Service for demonstration
    private final IADService adService = new MockADService();

    public void initialize() {
        // Setup note type toggle group
        typeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle != null) {
                ToggleButton selectedBtn = (ToggleButton) newToggle;
                String selectedType = selectedBtn.getText();
                System.out.println("Tipo de nota seleccionado: " + selectedType);
            } else {
                oldToggle.setSelected(true);
            }
        });

        btnTypeEntrega.setSelected(true);

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
            new AssetItem("Notebook", "HP", "ProBook 450 G9", "Cargador original, Mouse USB", "SN123456", "AF789012"),
            new AssetItem("Monitor", "Samsung", "F24T35", "Cable HDMI, Cable de poder", "SN789012", "AF345678"),
            new AssetItem("Teclado", "Logitech", "K120", "Nuevo en caja", "SN345678", "IT-00000008")
        );

        // Mock Countables (The bottom table)
        countableList.addAll(
            new CountableItem("Cable UTP 2mts", "Generic", "CAT6", 1, "Gris"),
            new CountableItem("Adaptador HDMI a VGA", "Nisuta", "NS-ADHV", 10, "Blanco"),
            new CountableItem("Mouse Pad", "Generic", "Standard", 1, "Negro - Siglo 21")
        );
    }

    @FXML
    private void handleADSearch() {
        String dni = txtUserDni.getText();
        String name = txtUserName.getText();
        String username = txtUserAccount.getText();

        // Case: No criteria entered
        if (dni.isEmpty() && name.isEmpty() && username.isEmpty()) {
            triggerFeedback("⚠ Ingrese criterios de búsqueda", "#f59e0b");
            return;
        }

        List<ADUser> results = adService.search(dni, name, username);

        // Case: No results found
        if (results.isEmpty()) {
            triggerFeedback("✘ Usuario no encontrado", "#ef4444");
            return;
        }

        // Case: At least one result found
        if (results.size() == 1) {
            ADUser user = results.get(0);
            updateUserData(user.getDni(), user.getFullName(), user.getUsername(), user.getEmail());
            triggerFeedback("✔ Usuario cargado", "#0c8570");
        } else {
            showUserSelectionDialog(results);
        }
    }

    // Callback method for the AD lookup dialog
    public void updateUserData(String dni, String name, String username, String email) {
        txtUserDni.setText(dni);
        txtUserName.setText(name);
        txtUserAccount.setText(username);
        lblUserEmail.setText(email);
    }

    private void showUserSelectionDialog(List<ADUser> results) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/bunshock/note_app_for_it_frontend/views/ADUserSelectionView.fxml"));
            Parent root = loader.load();

            ADUserSelectionController controller = loader.getController();
            controller.setParentController(this);
            controller.setResults(results);

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setTitle("Búsqueda AD - Seleccionar Usuario");
            stage.setScene(new Scene(root));
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // AD lookup feedback animation and status label update
    public void triggerFeedback(String message, String hexColor) {
        Color startColor = Color.web(hexColor);
        Color endColor = Color.web("#cbd5e1");

        lblADStatus.setText(message);
        lblADStatus.setStyle("-fx-text-fill: " + hexColor + ";");
        lblADStatus.setOpacity(1.0);

        Timeline timeline = new Timeline();
        
        int holdMs = 1000; 
        int fadeMs = 2000;
        int totalMs = holdMs + fadeMs;
        int frames = 60; 

        for (int i = 0; i <= frames; i++) {
            double currentTime = (double) i / frames * totalMs;
            double fraction;

            if (currentTime <= holdMs) {
                fraction = 0.0;
            } else {
                fraction = (currentTime - holdMs) / fadeMs;
            }

            KeyFrame keyFrame = new KeyFrame(
                Duration.millis(currentTime),
                e -> {
                    Color mixed = startColor.interpolate(endColor, fraction);
                    String hex = String.format("#%02x%02x%02x", 
                        (int)(mixed.getRed() * 255), 
                        (int)(mixed.getGreen() * 255), 
                        (int)(mixed.getBlue() * 255));
                    
                    String borderStyle = "-fx-border-color: " + hex + "; -fx-border-width: 1.5;";
                    txtUserDni.setStyle(borderStyle);
                    txtUserName.setStyle(borderStyle);
                    txtUserAccount.setStyle(borderStyle);

                    lblADStatus.setOpacity(1.0 - fraction);
                }
            );
            timeline.getKeyFrames().add(keyFrame);
        }

        timeline.setOnFinished(e -> {
            resetStyles();
            lblADStatus.setText("");
        });
        
        timeline.play();
    }

    private void resetStyles() {
        txtUserDni.setStyle("");
        txtUserName.setStyle("");
        txtUserAccount.setStyle("");
    }

    @FXML void handleClearUserFields() {
        txtUserDni.clear();
        txtUserName.clear();
        txtUserAccount.clear();
        lblUserEmail.setText("email: ");
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

}