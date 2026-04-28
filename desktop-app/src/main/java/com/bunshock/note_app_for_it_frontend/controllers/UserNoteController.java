package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.util.List;

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
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

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
            }
        });

        btnTypeEntrega.setSelected(true);

        // Set up empty tables
        setupAssetTable();
        setupCountableTable();
    }

    @FXML
    private void handleADSearch() {
        String dni = txtUserDni.getText();
        String name = txtUserName.getText();
        String username = txtUserAccount.getText();

        // Only search in AD if at least one field is filled
        if (dni.isEmpty() && name.isEmpty() && username.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setContentText("Por favor, ingrese al menos un criterio (DNI, Nombre o Username) para buscar.");
            alert.show();
            return;
        }

        List<ADUser> results = adService.search(dni, name, username);

        if (results.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Búsqueda AD");
            alert.setHeaderText(null);
            alert.setContentText("No se encontró ningún usuario con los criterios ingresados.");
            alert.showAndWait();
        } else if (results.size() == 1) {
            ADUser user = results.get(0);
            updateUserData(user.getDni(), user.getFullName(), user.getUsername(), user.getEmail());
            animateSuccess();
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

    // A small animation for success AD lookup feedback
    public void animateSuccess() {
        // Start and end colors
        Color startColor = Color.web("#0c8570");
        Color endColor = Color.web("#cbd5e1");

        Timeline timeline = new Timeline();
        
        int frames = 40; 
        int durationMs = 2000;

        for (int i = 0; i <= frames; i++) {
            double fraction = (double) i / frames;
            KeyFrame keyFrame = new KeyFrame(
                Duration.millis(durationMs * fraction),
                e -> {
                    // Mix the colors based on how far we are in the 2 seconds
                    Color mixed = startColor.interpolate(endColor, fraction);
                    
                    // Convert the Color object to a CSS hex string
                    String hex = String.format("#%02x%02x%02x", 
                        (int)(mixed.getRed() * 255), 
                        (int)(mixed.getGreen() * 255), 
                        (int)(mixed.getBlue() * 255));
                    
                    // Apply the style
                    String style = "-fx-border-color: " + hex + "; -fx-border-width: 1.5;";
                    txtUserDni.setStyle(style);
                    txtUserName.setStyle(style);
                    txtUserAccount.setStyle(style);
                }
            );
            timeline.getKeyFrames().add(keyFrame);
        }
        
        // Reset the style to return control to the CSS file
        timeline.setOnFinished(e -> {
            txtUserDni.setStyle("");
            txtUserName.setStyle("");
            txtUserAccount.setStyle("");
        });

        timeline.play();
    }

    @FXML void handleClearUserFields() {
        txtUserDni.clear();
        txtUserName.clear();
        txtUserAccount.clear();
        lblUserEmail.setText("");

        txtUserDni.requestFocus();
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