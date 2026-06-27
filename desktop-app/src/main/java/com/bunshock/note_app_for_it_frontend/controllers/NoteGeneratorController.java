package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.NoteGenerationService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.utils.ViewFactory;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Callback;

public class NoteGeneratorController {

    @FXML private ToggleButton btnUserNote;
    @FXML private ToggleButton btnProviderNote;
    @FXML private ToggleGroup typeGroup;
    @FXML private StackPane dynamicContentArea;

    @FXML private TableView<AssetItem> tblAssets;
    @FXML private TableColumn<AssetItem, String> colAssetType;
    @FXML private TableColumn<AssetItem, String> colAssetBrand;
    @FXML private TableColumn<AssetItem, String> colAssetModel;
    @FXML private TableColumn<AssetItem, String> colAssetSerial;
    @FXML private TableColumn<AssetItem, String> colAssetAF;
    @FXML private TableColumn<AssetItem, String> colAssetObs;
    @FXML private TableColumn<AssetItem, Void> colAssetActions;

    @FXML private TableView<CountableItem> tblCountables;
    @FXML private TableColumn<CountableItem, String> colCountType;
    @FXML private TableColumn<CountableItem, String> colCountBrand;
    @FXML private TableColumn<CountableItem, String> colCountModel;
    @FXML private TableColumn<CountableItem, Integer> colCountQty;
    @FXML private TableColumn<CountableItem, String> colCountObs;
    @FXML private TableColumn<CountableItem, Void> colCountActions;

    @FXML private TextField txtObservations;
    @FXML private Button btnAddItem;

    private final ObservableList<AssetItem> assetList = FXCollections.observableArrayList();
    private final ObservableList<CountableItem> countableList = FXCollections.observableArrayList();

    private ViewFactory viewFactory;
    private Tooltip limitTooltip;

    public void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
        showUserNoteView();
    }

    public void initialize() {
        btnUserNote.setOnAction(e -> showUserNoteView());
        btnProviderNote.setOnAction(e -> showProviderNoteView());

        typeGroup.selectedToggleProperty().addListener((obs, old, next) -> {
            if (next == null) old.setSelected(true);
        });

        setupAssetTable();
        setupCountableTable();

        colAssetActions.setCellFactory(createActionCellFactory(
            this::handleEditAsset, asset -> { assetList.remove(asset); updateAddButtonState(); }));

        colCountActions.setCellFactory(createActionCellFactory(
            this::handleEditCountable, countable -> { countableList.remove(countable); updateAddButtonState(); }));

        updateAddButtonState();
    }

    private void showUserNoteView() {
        if (viewFactory != null) dynamicContentArea.getChildren().setAll(viewFactory.getUserNoteView());
    }

    private void showProviderNoteView() {
        if (viewFactory != null) dynamicContentArea.getChildren().setAll(viewFactory.getProviderNoteView());
    }

    private <T> Callback<TableColumn<T, Void>, TableCell<T, Void>> createActionCellFactory(
            Consumer<T> editAction, Consumer<T> deleteAction) {
        return param -> new TableCell<>() {
            private final Button btnEdit = new Button("✏");
            private final Button btnDelete = new Button("🗑");
            private final HBox container = new HBox(14, btnEdit, btnDelete);

            {
                container.setAlignment(Pos.CENTER);
                btnEdit.getStyleClass().add("button-icon-edit");
                btnDelete.getStyleClass().add("button-icon-delete");
                btnEdit.setOnAction(e -> {
                    T item = getTableView().getItems().get(getIndex());
                    if (item != null) editAction.accept(item);
                });
                btnDelete.setOnAction(e -> {
                    T item = getTableView().getItems().get(getIndex());
                    if (item != null) deleteAction.accept(item);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setAlignment(Pos.CENTER);
                setGraphic(empty ? null : container);
            }
        };
    }

    private void handleEditAsset(AssetItem asset) {
        openItemDialog(ctrl -> ctrl.prefillAsset(asset), true);
    }

    private void handleEditCountable(CountableItem countable) {
        openItemDialog(ctrl -> ctrl.prefillCountable(countable), true);
    }

    @FXML
    private void handleAddItem() {
        if (isAtItemLimit()) return;
        openItemDialog(ctrl -> {}, false);
    }

    private void openItemDialog(Consumer<ItemDialogController> setup, boolean editMode) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/views/ItemDialogView.fxml"));
            Parent root = loader.load();
            ItemDialogController ctrl = loader.getController();
            ctrl.setParentController(this);
            setup.accept(ctrl);

            Stage stage = new Stage();
            stage.initStyle(StageStyle.TRANSPARENT);
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene dialogScene = new Scene(root, 544, 580);
            dialogScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            dialogScene.getStylesheets().add(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
            stage.setScene(dialogScene);
            stage.setOnHidden(e -> updateAddButtonState());
            stage.show();

            javafx.geometry.Bounds btn = btnAddItem.localToScreen(btnAddItem.getBoundsInLocal());
            javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
            double x = Math.min(btn.getMaxX() - stage.getWidth(), screen.getMaxX() - stage.getWidth());
            double y = Math.min(btn.getMaxY() + 6, screen.getMaxY() - stage.getHeight());
            stage.setX(Math.max(screen.getMinX(), x));
            stage.setY(Math.max(screen.getMinY(), y));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isAtItemLimit() {
        int limit = ConfigService.getInstance().getConfig().noteItemLimit;
        return (assetList.size() + countableList.size()) >= limit;
    }

    private void updateAddButtonState() {
        boolean atLimit = isAtItemLimit();
        if (atLimit) {
            int limit = ConfigService.getInstance().getConfig().noteItemLimit;
            btnAddItem.getStyleClass().removeAll("button-primary");
            if (!btnAddItem.getStyleClass().contains("button-limit-reached")) {
                btnAddItem.getStyleClass().add("button-limit-reached");
            }
            if (limitTooltip == null) {
                limitTooltip = new Tooltip("Límite de " + limit + " ítems por nota alcanzado");
            }
            btnAddItem.setTooltip(limitTooltip);
        } else {
            btnAddItem.getStyleClass().removeAll("button-limit-reached");
            if (!btnAddItem.getStyleClass().contains("button-primary")) {
                btnAddItem.getStyleClass().add("button-primary");
            }
            btnAddItem.setTooltip(null);
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
    }

    private void setupCountableTable() {
        colCountType.setCellValueFactory(d -> d.getValue().getType());
        colCountBrand.setCellValueFactory(d -> d.getValue().getBrand());
        colCountModel.setCellValueFactory(d -> d.getValue().getModel());
        colCountQty.setCellValueFactory(d -> d.getValue().getQuantity().asObject());
        colCountObs.setCellValueFactory(d -> d.getValue().getObservations());
        tblCountables.setItems(countableList);
    }

    public void addAsset(AssetItem item) { assetList.add(item); }
    public void addCountable(CountableItem item) { countableList.add(item); }

    public ObservableList<AssetItem> getAssetList() { return assetList; }
    public ObservableList<CountableItem> getCountableList() { return countableList; }
    public String getObservations() { return txtObservations.getText().trim(); }

    public boolean isUserNote() {
        return typeGroup.getSelectedToggle() == btnUserNote;
    }

    @FXML
    private void handleClearForm() {
        txtObservations.clear();
        assetList.clear();
        countableList.clear();
        updateAddButtonState();

        if (isUserNote() && viewFactory != null && viewFactory.getUserNoteController() != null) {
            viewFactory.getUserNoteController().clearAllFields();
        } else if (!isUserNote() && viewFactory != null && viewFactory.getProviderNoteController() != null) {
            viewFactory.getProviderNoteController().clearAllFields();
        }
    }

    @FXML
    private void handleGenerateNote() {
        try {
            if (isUserNote() && !checkGlpiAssignments()) return;

            NoteGenerationService generator = new NoteGenerationService();
            String html;
            NoteReport report = new NoteReport();

            if (isUserNote()) {
                UserNoteController unc = viewFactory.getUserNoteController();
                String profileType = unc.getSelectedNoteType();
                html = generator.generateUserNote(
                    profileType,
                    unc.getUserName(), unc.getUserDni(), unc.getUserEmail(),
                    unc.getMotivo(),
                    assetList, countableList, getObservations()
                );
                report.setUserName(unc.getUserName());
                report.setUserDni(unc.getUserDni());
                report.setUserEmail(unc.getUserEmail());
                report.setMotivo(unc.getMotivo());
                report.setProfileType(profileType);
            } else {
                ProviderNoteController pnc = viewFactory.getProviderNoteController();
                html = generator.generateProviderNote(
                    pnc.getProviderName(), pnc.getCuit(),
                    pnc.getResponsibleName(), pnc.getResponsibleDni(),
                    pnc.getMotivo(),
                    assetList, countableList, getObservations()
                );
                report.setProviderName(pnc.getProviderName());
                report.setCuit(pnc.getCuit());
                report.setMotivo(pnc.getMotivo());
                report.setProfileType("Entrega - Proveedor");
            }

            report.setCreatedAt(java.time.LocalDateTime.now());
            openPreview(html, report.getProfileType(), report);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean checkGlpiAssignments() {
        UserNoteController unc = viewFactory != null ? viewFactory.getUserNoteController() : null;
        if (unc == null) return true;
        if (!"DEVOLUCIÓN".equals(unc.getSelectedNoteType())) return true;

        String userAccount = unc.getUserAccount();
        List<String> conflicts = new ArrayList<>();

        for (AssetItem asset : assetList) {
            String sn = asset.getSerial().get();
            if (sn == null || sn.isEmpty()) continue;
            String assigned = ServiceLocator.getInstance().getGlpiService().getAssignedUserInGlpi(sn);
            if (assigned != null && !assigned.equalsIgnoreCase(userAccount)) {
                conflicts.add(asset.getType().get() + " S/N: " + sn + " (asignado a: " + assigned + ")");
            }
        }

        if (conflicts.isEmpty()) return true;

        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle("Conflicto de Asignación en GLPI");
        alert.setHeaderText("No se puede generar la nota de devolución");
        alert.setContentText("Los siguientes activos no están asignados al usuario seleccionado:\n\n"
            + String.join("\n", conflicts));
        alert.showAndWait();
        return false;
    }

    private void openPreview(String html, String profileType, NoteReport report) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/views/NotePreviewView.fxml"));
        Parent root = loader.load();
        NotePreviewController ctrl = loader.getController();
        ctrl.loadPreview(html, profileType, report, assetList, countableList);

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Previsualización de Nota");
        stage.setScene(new Scene(root));
        stage.show();
    }
}
