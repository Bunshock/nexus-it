package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.catalog.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.catalog.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.catalog.SnValidation;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.IHistoryService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.animation.FadeTransition;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class ItemDialogController {

    @FXML private HBox hboxHeader;
    @FXML private Label lblDialogTitle;

    @FXML private ComboBox<EquipmentType> cmbType;
    @FXML private ComboBox<EquipmentBrand> cmbBrand;
    @FXML private ComboBox<EquipmentModel> cmbModel;

    @FXML private VBox containerAssetFields;
    @FXML private CheckBox chkSinSN;
    @FXML private CheckBox chkUppercase;
    @FXML private TextField txtSerial;
    @FXML private Label lblSnValidation;
    @FXML private javafx.scene.text.TextFlow flowSnPattern;
    @FXML private CheckBox chkEnableAF;
    @FXML private TextField txtAF;

    @FXML private VBox containerCountableFields;
    @FXML private TextField txtQty;

    @FXML private HBox hboxGlpiStatus;
    @FXML private ProgressIndicator progressGlpi;
    @FXML private Label lblGlpiStatus;

    @FXML private CheckBox chkModifiesStock;
    @FXML private TextField txtObs;
    @FXML private Button btnSave;

    private static final String DEFAULT_GENERIC_LABEL = "Genérico / Otro";

    // BRAND has no structural way to mark "this is the fallback row" the way EquipmentModel
    // does (brandTypeId == null) — no scoping FK to leave null — so it's still identified by
    // name. Duplicated from SqliteEquipmentService's identical helper per this codebase's
    // no-shared-abstraction convention.
    private String genericLabel() {
        try {
            AppConfig.CatalogConfig catalog = ConfigService.getInstance().getConfig().catalog;
            if (catalog != null && catalog.genericLabel != null && !catalog.genericLabel.isBlank()) {
                return catalog.genericLabel.trim();
            }
        } catch (IllegalStateException notLoaded) {
            // ConfigService not loaded in this context (e.g. some test setups) — use the default
        }
        return DEFAULT_GENERIC_LABEL;
    }

    // EquipmentModel is identified structurally (brandTypeId == null); everything else
    // (EquipmentBrand) still falls back to a name comparison.
    private boolean isGenericItem(Object item) {
        if (item instanceof EquipmentModel model) return model.isGlobalGeneric();
        return genericLabel().equals(item.toString());
    }

    // "Most used" pinning at the top of Type/Brand/Model combos — see CLAUDE.md's
    // "Most-used item pinning" section for the reasoning behind these numbers.
    private static final int MOST_USED_WINDOW_DAYS = 30;
    private static final int MOST_USED_MIN_USES = 2;
    private static final int MOST_USED_LIMIT = 3;

    private ItemDialogHost parentController;
    private IEquipmentService equipmentService;
    private IHistoryService historyService;
    private AssetItem editingAsset;
    private CountableItem editingCountable;
    // Only ever non-null while chkModifiesStock is unchecked — set from the confirmation popup's
    // mandatory reason field, cleared the moment the checkbox is re-checked.
    private String stockExceptionReason;

    private int pinnedTypeCount;
    private int pinnedBrandCount;
    private int pinnedModelCount;

    private static final int OBSERVATIONS_MAX_LENGTH = 200;
    // Matches NOTE_ITEM_ASSET.serial_number's NVARCHAR(255) bound on SQL Server — SQLite itself
    // never enforces this, so the app-layer cap is the only thing stopping a pasted value from
    // saving fine locally and then failing with a truncation error against a configured remote
    // database — this field had no cap of any kind before, unlike every other
    // free-text field in this app. A/F is derived from S/N (prefix + separator + serial) and not
    // separately capped here — it's a read-only display field, nothing is ever typed into it
    // directly, and prefix/separator are short config values, so this bound already keeps A/F
    // well within its own NVARCHAR(255) bound in every realistic case.
    private static final int SERIAL_MAX_LENGTH = 255;

    public void initialize() {
        equipmentService = ServiceLocator.getInstance().getEquipmentService();
        historyService = ServiceLocator.getInstance().getHistoryService();
        txtObs.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= OBSERVATIONS_MAX_LENGTH ? change : null));
        txtSerial.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SERIAL_MAX_LENGTH ? change : null));
        txtQty.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.isEmpty()) return change;
            if (newText.matches("[1-9][0-9]{0,2}")) return change;
            return null;
        }));

        populateTypeCombo();
        applyGenericCellFactory(cmbType, () -> pinnedTypeCount);
        applyGenericCellFactory(cmbBrand, () -> pinnedBrandCount);
        applyGenericCellFactory(cmbModel, () -> pinnedModelCount);

        cmbType.valueProperty().addListener((obs, old, type) -> onTypeSelected(type));
        cmbBrand.valueProperty().addListener((obs, old, brand) -> onBrandSelected(brand));
        cmbModel.valueProperty().addListener((obs, old, model) -> onModelSelected(model));

        txtSerial.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) applySnFormatting();
        });

        txtSerial.textProperty().addListener((obs, old, val) -> {
            validateSnLength();
            recomputeAf();
        });
    }

    private void populateTypeCombo() {
        List<String> mostUsedTypes = historyService.getMostUsedTypeNames(
            MOST_USED_WINDOW_DAYS, MOST_USED_MIN_USES, MOST_USED_LIMIT);
        List<EquipmentType> types = equipmentService.getAllTypes();
        cmbType.setItems(FXCollections.observableArrayList(reorderWithPinned(
            types, mostUsedTypes, EquipmentType::getName, count -> pinnedTypeCount = count)));
    }

    private static final String DIVIDER_STYLE =
        "-fx-border-color: #e2e8f0 transparent transparent transparent; -fx-border-width: 1 0 0 0;";
    private static final String GENERIC_STYLE = "-fx-font-style: italic; -fx-text-fill: #94a3b8;";

    private <T> void applyGenericCellFactory(ComboBox<T> combo, IntSupplier pinnedCountSupplier) {
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.toString());
                    boolean isGeneric = isGenericItem(item);
                    String style = isGeneric ? GENERIC_STYLE : "";
                    int pinnedCount = pinnedCountSupplier.getAsInt();
                    // Two independent dividers can appear in the same list: one above the first
                    // "rest" item after the pinned most-used items (top), and one above the
                    // Genérico/Otro fallback, which addType/onBrandSelected always appends last
                    // (bottom) — mirrors the pinned-items separator, just at the other end.
                    if (pinnedCount > 0 && getIndex() == pinnedCount) {
                        style += DIVIDER_STYLE;
                    } else if (isGeneric) {
                        style += DIVIDER_STYLE;
                    }
                    setStyle(style);
                }
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(combo.getPromptText());
                    setStyle("-fx-text-fill: #9ca3af;");
                } else {
                    setText(item.toString());
                    setStyle(isGenericItem(item) ? GENERIC_STYLE : "");
                }
            }
        });
    }

    // Moves items whose name matches mostUsedNames (in rank order) to the front of the
    // list, reports how many were pinned via pinnedCountSetter, and leaves the list
    // untouched (pinnedCount = 0, no separator) when nothing qualifies as "most used" —
    // per the requirement that an unconfigured combobox looks like a normal one.
    private <T> List<T> reorderWithPinned(List<T> allItems, List<String> mostUsedNames,
            Function<T, String> nameFn, IntConsumer pinnedCountSetter) {
        List<T> pinned = new ArrayList<>();
        for (String name : mostUsedNames) {
            allItems.stream()
                .filter(item -> nameFn.apply(item).equalsIgnoreCase(name))
                .findFirst()
                .ifPresent(pinned::add);
        }
        if (pinned.isEmpty()) {
            pinnedCountSetter.accept(0);
            return allItems;
        }
        List<T> rest = new ArrayList<>(allItems);
        rest.removeAll(pinned);
        List<T> result = new ArrayList<>(pinned);
        result.addAll(rest);
        pinnedCountSetter.accept(pinned.size());
        return result;
    }

    private void onTypeSelected(EquipmentType type) {
        cmbBrand.setValue(null);
        cmbModel.setValue(null);
        cmbBrand.setDisable(type == null);
        cmbModel.setDisable(true);
        clearSnLabels();
        cmbType.setStyle("-fx-padding: 5 8;");
        cmbBrand.setStyle("-fx-padding: 5 8;");
        cmbModel.setStyle("-fx-padding: 5 8;");

        if (type == null) {
            hideEquipmentFields();
            btnSave.setDisable(true);
            return;
        }

        List<EquipmentBrand> brands = new ArrayList<>(equipmentService.getBrandsForType(type.getId()));
        if (brands.stream().noneMatch(b -> genericLabel().equals(b.getName()))) {
            equipmentService.getAllBrands().stream()
                .filter(b -> genericLabel().equals(b.getName()))
                .findFirst()
                .ifPresent(brands::add);
        }
        List<String> mostUsedBrands = historyService.getMostUsedBrandNames(
            type.getName(), MOST_USED_WINDOW_DAYS, MOST_USED_MIN_USES, MOST_USED_LIMIT);
        cmbBrand.setItems(FXCollections.observableArrayList(reorderWithPinned(
            brands, mostUsedBrands, EquipmentBrand::getName, count -> pinnedBrandCount = count)));

        showFieldsForType(type.isAsset());
        updateSinSnForType(type);
        btnSave.setDisable(false);
    }

    // Generalized (2026-07-13) from a hardcoded "Notebook".equals(type.getName()) check to
    // TYPE.requires_serial — renaming the Notebook type no longer silently breaks the rule.
    private void updateSinSnForType(EquipmentType type) {
        if (type == null || !type.isAsset()) return;
        boolean requiresSerial = type.isRequiresSerial();
        chkSinSN.setDisable(requiresSerial);
        if (requiresSerial) {
            chkSinSN.setSelected(false);
            txtSerial.setDisable(false);
            chkSinSN.setTooltip(new Tooltip("Este tipo de equipo siempre requiere número de serie"));
        } else {
            chkSinSN.setTooltip(new Tooltip("Marcar solo en casos excepcionales"));
        }
    }

    private void onBrandSelected(EquipmentBrand brand) {
        cmbModel.setValue(null);
        cmbBrand.setStyle("-fx-padding: 5 8;");
        cmbModel.setStyle("-fx-padding: 5 8;");
        EquipmentType type = cmbType.getValue();
        if (brand == null || type == null) {
            cmbModel.setDisable(true);
            return;
        }

        // getModelsForBrandAndType() always includes the global generic model regardless of
        // brand/type — no BRAND_TYPE_LINK needs to exist first, even for a brand+type
        // combination that's never been linked before (e.g. the global Genérico/Otro brand
        // itself, or a real brand picked for a type it's never been paired with).
        List<EquipmentModel> models = new ArrayList<>(
            equipmentService.getModelsForBrandAndType(brand.getId(), type.getId()));
        // That query's ORDER BY name sorts the global generic row alphabetically alongside
        // real models instead of always last (unlike Brand's own query, which never includes
        // the generic row at all — it's appended separately in onTypeSelected() above, always
        // landing at the true end of the list). Pull it out and re-append it here so the
        // divider drawn by applyGenericCellFactory() lines up with the actual last row, same
        // "generic sits last" fix DatabaseSectionController.refreshModelsForBrandType() already
        // applies for its own Model list.
        models.stream().filter(this::isGenericItem).findFirst().ifPresent(generic -> {
            models.remove(generic);
            models.add(generic);
        });
        List<String> mostUsedModels = historyService.getMostUsedModelNames(
            type.getName(), brand.getName(), MOST_USED_WINDOW_DAYS, MOST_USED_MIN_USES, MOST_USED_LIMIT);
        cmbModel.setItems(FXCollections.observableArrayList(reorderWithPinned(
            models, mostUsedModels, EquipmentModel::getName, count -> pinnedModelCount = count)));
        cmbModel.setDisable(false);
        clearSnLabels();
    }

    private void onModelSelected(EquipmentModel model) {
        cmbModel.setStyle("-fx-padding: 5 8;");
        clearSnLabels();
        if (model == null || model.getId() < 0) return;
        Optional<SnValidation> validation = equipmentService.getSnValidation(model.getId());
        if (validation.isPresent() && validation.get().isActive()) {
            showSnHints(validation.get());
            if (containerAssetFields.isVisible() && !chkSinSN.isSelected()) {
                btnSave.setDisable(true);
            }
        }
    }

    private void showFieldsForType(boolean isAsset) {
        containerAssetFields.setVisible(isAsset);
        containerAssetFields.setManaged(isAsset);
        containerCountableFields.setVisible(!isAsset);
        containerCountableFields.setManaged(!isAsset);
    }

    private void hideEquipmentFields() {
        containerAssetFields.setVisible(false);
        containerAssetFields.setManaged(false);
        containerCountableFields.setVisible(false);
        containerCountableFields.setManaged(false);
    }

    @FXML
    private void handleSinSnToggle() {
        boolean sinSN = chkSinSN.isSelected();
        txtSerial.setDisable(sinSN);
        if (sinSN) {
            txtSerial.clear();
            txtSerial.setPromptText("Deshabilitado");
            clearSnLabels();
            btnSave.setDisable(false);
        } else {
            txtSerial.setPromptText("Ingrese S/N...");
            validateSnLength();
        }
        // Nothing to derive A/F from with no serial number.
        chkEnableAF.setDisable(sinSN);
        if (sinSN) {
            chkEnableAF.setSelected(false);
            handleAfToggle();
        } else {
            recomputeAf();
        }
    }

    @FXML
    private void handleAfToggle() {
        boolean enabled = chkEnableAF.isSelected();
        txtAF.setDisable(!enabled);
        txtAF.setStyle("");
        txtAF.setPromptText(enabled ? "Ingrese S/N para calcular A/F..." : "Deshabilitado");
        recomputeAf();
    }

    // Unchecking "Modifica stock" is an exceptional action (e.g. formalizing a delivery that
    // already happened informally, with no stock movement actually needed) — require an explicit
    // confirmation AND a written reason, not a plain click, so it can't be toggled off by accident
    // and so there's a real audit trail for admins reviewing the note later. Re-checking it needs
    // no confirmation, since re-enabling normal behavior is never the risky direction — it also
    // discards whatever reason was previously entered, since it no longer applies.
    @FXML
    private void handleModifiesStockToggle() {
        if (chkModifiesStock.isSelected()) {
            stockExceptionReason = null;
            return;
        }
        String reason = confirmDisableStockModification();
        if (reason == null) {
            chkModifiesStock.setSelected(true);
        } else {
            stockExceptionReason = reason;
        }
    }

    private static final String STOCK_EXCEPTION_TITLE = "Excepción de modificación de stock";
    private static final String STOCK_EXCEPTION_MESSAGE =
        "Al desmarcar esta opción, este ítem NO modificará el stock cuando la nota sea aprobada.\n\n"
        + "Use esta opción solo en casos excepcionales, por ejemplo: al generar una nota para "
        + "formalizar la entrega de un equipo que la persona ya tenía en su poder, sin que la "
        + "entrega se haya registrado formalmente en su momento.\n\n"
        + "Un administrador verá esta excepción, junto con el motivo indicado, al revisar la nota "
        + "para aprobarla.";
    private static final int STOCK_EXCEPTION_REASON_MAX_LENGTH = 300;

    // Returns the entered reason on confirm, or null if the technician cancelled — the caller
    // uses null to distinguish "cancelled" from "confirmed with an (impossible, since mandatory)
    // blank reason."
    private String confirmDisableStockModification() {
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(btnSave.getScene().getWindow());
        String[] result = {null};

        Label title = new Label(STOCK_EXCEPTION_TITLE);
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #1a1a1a;");

        Label message = new Label(STOCK_EXCEPTION_MESSAGE);
        message.setWrapText(true);
        message.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");

        Label lblReason = new Label("MOTIVO *");
        lblReason.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 0.5;");

        TextArea txtReason = new TextArea();
        txtReason.setPromptText("Explique por qué este ítem no debe modificar el stock...");
        txtReason.setWrapText(true);
        txtReason.setPrefRowCount(3);
        txtReason.setStyle("-fx-font-size: 12px;");
        txtReason.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= STOCK_EXCEPTION_REASON_MAX_LENGTH ? change : null));
        VBox reasonGroup = new VBox(4, lblReason, txtReason);

        Button btnCancel = new Button("Cancelar");
        btnCancel.setStyle("-fx-background-color: #e2e8f0; -fx-text-fill: #334155; -fx-background-radius: 6; -fx-padding: 8 16;");
        btnCancel.setOnAction(e -> stage.close());

        Button btnConfirm = new Button("Confirmar");
        btnConfirm.setStyle("-fx-background-color: #f97316; -fx-text-fill: white; -fx-background-radius: 6; -fx-padding: 8 16; -fx-font-weight: bold;");
        btnConfirm.setDisable(true);
        btnConfirm.setOnAction(e -> { result[0] = txtReason.getText().trim(); stage.close(); });

        txtReason.textProperty().addListener((obs, old, val) ->
            btnConfirm.setDisable(val == null || val.trim().isEmpty()));

        HBox buttons = new HBox(8, btnCancel, btnConfirm);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(14, title, message, reasonGroup, buttons);
        card.setMaxWidth(380);
        card.setPrefWidth(380);
        card.setStyle("""
            -fx-background-color: #f97316, white;
            -fx-background-radius: 12, 10;
            -fx-background-insets: 0, 2;
            -fx-padding: 24;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 5);
            """);

        StackPane wrapper = new StackPane(card);
        wrapper.setStyle("-fx-background-color: transparent; -fx-padding: 20;");
        Scene scene = new Scene(wrapper);
        scene.setFill(Color.TRANSPARENT);
        java.net.URL cssUrl = getClass().getResource("/com/bunshock/note_app_for_it_frontend/css/styles.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();

        return result[0];
    }

    private void applySnFormatting() {
        if (chkSinSN.isSelected()) return;
        String value = txtSerial.getText().trim();
        if (value.isEmpty()) return;
        if (chkUppercase.isSelected()) txtSerial.setText(value.toUpperCase());
        validateSnLength();
    }

    private void validateSnLength() {
        EquipmentModel model = cmbModel.getValue();
        if (model == null || model.getId() < 0) return;
        if (!containerAssetFields.isVisible()) return;
        if (chkSinSN.isSelected()) return;
        Optional<SnValidation> validation = equipmentService.getSnValidation(model.getId());
        if (validation.isEmpty() || !validation.get().isActive()) return;

        SnValidation rule = validation.get();
        String sn = txtSerial.getText().trim();
        String regex = rule.getRegexPattern();

        boolean lengthOk = true;
        boolean patternOk = true;

        OptionalInt expectedLen = rule.deriveExpectedLength();
        if (expectedLen.isPresent()) {
            int expected = expectedLen.getAsInt();
            lengthOk = sn.length() == expected;
            if (!lengthOk) {
                lblSnValidation.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 10px;");
                lblSnValidation.setText("Longitud incorrecta: se esperan " + expected + " caracteres (actual: " + sn.length() + ")");
            } else {
                lblSnValidation.setStyle("-fx-text-fill: #22c55e; -fx-font-size: 10px;");
                lblSnValidation.setText("Longitud correcta (" + expected + " car.)");
            }
        }

        if (regex != null) {
            // A syntactically invalid admin-authored regex (SettingsController's own save now
            // validates this too, but an already-saved bad pattern from before that check existed
            // — or any other write path — must not crash S/N entry for every technician typing
            // against this model). Fail safe: skip the pattern check rather than block saving
            // over an admin's own config mistake, same "fail safely" precedent this project
            // already applies to AD/GLPI/SMTP unreachability.
            try {
                patternOk = sn.matches(regex);
            } catch (java.util.regex.PatternSyntaxException invalidRegex) {
                patternOk = true;
            }
            if (!patternOk) {
                setPatternFlow("Formato inválido. Patrón: ", "#ef4444", rule);
            } else {
                setPatternFlow("Formato válido", "#22c55e", null);
            }
        }

        btnSave.setDisable(!lengthOk || !patternOk);
    }

    private void showSnHints(SnValidation rule) {
        OptionalInt expectedLen = rule.deriveExpectedLength();
        if (expectedLen.isPresent()) {
            lblSnValidation.setStyle("-fx-text-fill: #64748b; -fx-font-size: 10px;");
            lblSnValidation.setText("Longitud esperada: " + expectedLen.getAsInt() + " car.");
        }
        if (rule.getRegexPattern() != null) {
            setPatternFlow("Patrón esperado: ", "#64748b", rule);
        }
    }

    private void setPatternFlow(String prefix, String prefixColor, SnValidation rule) {
        flowSnPattern.getChildren().clear();
        javafx.scene.text.Text prefixNode = new javafx.scene.text.Text(prefix);
        prefixNode.setStyle("-fx-fill: " + prefixColor + "; -fx-font-size: 10;");
        flowSnPattern.getChildren().add(prefixNode);
        if (rule == null) return;
        for (SnValidation.TemplateSegment seg : rule.templateParts()) {
            javafx.scene.text.Text t = new javafx.scene.text.Text(seg.text());
            if (seg.fixed()) {
                t.setStyle("-fx-fill: #334155; -fx-font-size: 10; -fx-font-weight: bold;");
            } else {
                t.setStyle("-fx-fill: #94a3b8; -fx-font-size: 10; -fx-font-style: italic;");
            }
            flowSnPattern.getChildren().add(t);
        }
    }

    private void clearSnLabels() {
        lblSnValidation.setText("");
        flowSnPattern.getChildren().clear();
    }

    // A/F is fully derived from S/N — prefix + separator + serial, recomputed live on every
    // S/N keystroke (txtSerial's textProperty listener) and whenever "Incluir A/F" is toggled.
    private void recomputeAf() {
        if (!chkEnableAF.isSelected()) {
            txtAF.setText("");
            return;
        }
        AppConfig.AfFormat fmt = ConfigService.getInstance().getConfig().afFormat;
        String sn = chkSinSN.isSelected() ? "" : txtSerial.getText().trim();
        txtAF.setText(sn.isEmpty() ? "" : fmt.prefix + fmt.separator + sn);
    }

    public void setParentController(ItemDialogHost parent) {
        this.parentController = parent;
    }

    public void prefillAsset(AssetItem asset) {
        editingAsset = asset;
        lblDialogTitle.setText("EDITAR EQUIPAMIENTO");
        btnSave.setText("Guardar Cambios");

        cmbType.getItems().stream()
            .filter(t -> t.getName().equals(asset.getType().get()))
            .findFirst().ifPresent(cmbType::setValue);

        cmbBrand.getItems().stream()
            .filter(b -> b.getName().equals(asset.getBrand().get()))
            .findFirst().ifPresent(cmbBrand::setValue);

        cmbModel.getItems().stream()
            .filter(m -> m.getName().equals(asset.getModel().get()))
            .findFirst().ifPresent(cmbModel::setValue);

        String sn = asset.getSerial().get();
        if (sn == null || sn.isEmpty()) {
            chkSinSN.setSelected(true);
            handleSinSnToggle();
        } else {
            txtSerial.setText(sn);
        }

        // A/F is fully derived from S/N (already set above by this point) — no raw value to
        // extract, just reflect whether this asset had it enabled and let recomputeAf() rebuild it.
        String af = asset.getAf().get();
        chkEnableAF.setSelected(af != null && !af.isEmpty());
        handleAfToggle();

        // Reflects the item's existing state — no confirmation prompt here, since the exceptional
        // choice (and its reason) was already made and confirmed when this item was first added.
        chkModifiesStock.setSelected(asset.isModifiesStock());
        stockExceptionReason = asset.isModifiesStock() ? null : asset.getModifiesStockReason();

        txtObs.setText(asset.getObservations().get());
    }

    public void prefillCountable(CountableItem countable) {
        editingCountable = countable;
        lblDialogTitle.setText("EDITAR EQUIPAMIENTO");
        btnSave.setText("Guardar Cambios");

        cmbType.getItems().stream()
            .filter(t -> t.getName().equals(countable.getType().get()))
            .findFirst().ifPresent(cmbType::setValue);

        cmbBrand.getItems().stream()
            .filter(b -> b.getName().equals(countable.getBrand().get()))
            .findFirst().ifPresent(cmbBrand::setValue);

        cmbModel.getItems().stream()
            .filter(m -> m.getName().equals(countable.getModel().get()))
            .findFirst().ifPresent(cmbModel::setValue);

        txtQty.setText(String.valueOf(countable.getQuantity().get()));
        chkModifiesStock.setSelected(countable.isModifiesStock());
        stockExceptionReason = countable.isModifiesStock() ? null : countable.getModifiesStockReason();
        txtObs.setText(countable.getObservations().get());
    }

    @FXML
    private void onSave() {
        if (!validateMandatoryFields()) return;

        EquipmentType type = cmbType.getValue();
        if (type.isAsset() && !chkSinSN.isSelected()) {
            String sn = txtSerial.getText().trim();
            if (!sn.isEmpty()) {
                runGlpiCheck(sn, this::doSave);
                return;
            }
        }
        doSave();
    }

    private boolean validateMandatoryFields() {
        boolean valid = true;
        EquipmentType type = cmbType.getValue();

        if (type == null) {
            cmbType.setStyle("-fx-border-color: #ef4444; -fx-border-width: 1.5; -fx-border-radius: 4; -fx-padding: 5 8;");
            valid = false;
        } else {
            cmbType.setStyle("-fx-padding: 5 8;");
        }

        if (cmbBrand.getValue() == null) {
            cmbBrand.setStyle("-fx-border-color: #ef4444; -fx-border-width: 1.5; -fx-border-radius: 4; -fx-padding: 5 8;");
            valid = false;
        } else {
            cmbBrand.setStyle("-fx-padding: 5 8;");
        }

        if (cmbModel.getValue() == null) {
            cmbModel.setStyle("-fx-border-color: #ef4444; -fx-border-width: 1.5; -fx-border-radius: 4; -fx-padding: 5 8;");
            valid = false;
        } else {
            cmbModel.setStyle("-fx-padding: 5 8;");
        }

        boolean isAssetSerial = type != null && type.isAsset();

        if (isAssetSerial && !chkSinSN.isSelected()) {
            if (txtSerial.getText().trim().isEmpty()) {
                txtSerial.setStyle("-fx-border-color: #ef4444; -fx-border-width: 1.5; -fx-border-radius: 4; -fx-background-radius: 4;");
                valid = false;
            } else {
                txtSerial.setStyle("");
            }
        }

        if (isAssetSerial && chkEnableAF.isSelected()) {
            if (txtAF.getText().trim().isEmpty()) {
                txtAF.setStyle("-fx-border-color: #ef4444; -fx-border-width: 1.5; -fx-border-radius: 4; -fx-background-radius: 4;");
                valid = false;
            } else {
                txtAF.setStyle("");
            }
        } else {
            txtAF.setStyle("");
        }

        return valid;
    }

    private void runGlpiCheck(String sn, Runnable onSuccess) {
        hboxGlpiStatus.setVisible(true);
        hboxGlpiStatus.setManaged(true);
        progressGlpi.setVisible(true);
        lblGlpiStatus.setText("Verificando en GLPI...");
        lblGlpiStatus.setStyle("-fx-text-fill: #64748b;");
        lblGlpiStatus.setOpacity(1.0);
        btnSave.setDisable(true);

        String typeName  = cmbType.getValue()  != null ? cmbType.getValue().getName()  : "";
        String brandName = cmbBrand.getValue() != null ? cmbBrand.getValue().getName() : "";
        String modelName = cmbModel.getValue() != null ? cmbModel.getValue().getName() : "";

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return ServiceLocator.getInstance().getGlpiService()
                    .assetExistsInGlpi(typeName, brandName, modelName, sn);
            }
        };

        task.setOnSucceeded(e -> {
            progressGlpi.setVisible(false);
            btnSave.setDisable(false);
            if (Boolean.TRUE.equals(task.getValue())) {
                hboxGlpiStatus.setVisible(false);
                hboxGlpiStatus.setManaged(false);
                onSuccess.run();
            } else {
                showGlpiError("Activo no encontrado en GLPI. Debe registrarse antes de continuar.");
            }
        });

        task.setOnFailed(e -> {
            progressGlpi.setVisible(false);
            btnSave.setDisable(false);
            showGlpiError("No se pudo conectar con GLPI. Intente nuevamente.");
        });

        Thread t = new Thread(task, "glpi-asset-check");
        t.setDaemon(true);
        t.start();
    }

    private void showGlpiError(String message) {
        lblGlpiStatus.setText(message);
        lblGlpiStatus.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 11px;");
        lblGlpiStatus.setOpacity(1.0);

        FadeTransition fade = new FadeTransition(Duration.millis(400), lblGlpiStatus);
        fade.setDelay(Duration.millis(3000));
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            hboxGlpiStatus.setVisible(false);
            hboxGlpiStatus.setManaged(false);
            lblGlpiStatus.setText("");
            lblGlpiStatus.setOpacity(1.0);
        });
        fade.play();
    }

    private void doSave() {
        EquipmentType type = cmbType.getValue();
        EquipmentBrand brand = cmbBrand.getValue();
        EquipmentModel model = cmbModel.getValue();
        String typeName  = type.getName();
        String brandName = brand.getName();
        String modelName = model.getName();
        String obs = txtObs.getText().trim();
        boolean modifiesStock = chkModifiesStock.isSelected();
        String modifiesStockReason = modifiesStock ? null : stockExceptionReason;

        if (type.isAsset()) {
            String sn = chkSinSN.isSelected() ? "" : txtSerial.getText().trim();
            if (!chkSinSN.isSelected() && chkUppercase.isSelected()) sn = sn.toUpperCase();
            String af = chkEnableAF.isSelected() ? txtAF.getText().trim() : "";

            if (editingAsset != null) {
                editingAsset.getType().set(typeName);
                editingAsset.getBrand().set(brandName);
                editingAsset.getModel().set(modelName);
                editingAsset.setTypeId(type.getId());
                editingAsset.setBrandId(brand.getId());
                editingAsset.setModelId(model.getId());
                editingAsset.getSerial().set(sn);
                editingAsset.getAf().set(af);
                editingAsset.getObservations().set(obs);
                editingAsset.setModifiesStock(modifiesStock);
                editingAsset.setModifiesStockReason(modifiesStockReason);
            } else {
                AssetItem newAsset = new AssetItem(typeName, brandName, modelName, obs, sn, af,
                    type.getId(), brand.getId(), model.getId(), modifiesStock);
                newAsset.setModifiesStockReason(modifiesStockReason);
                parentController.addAsset(newAsset);
            }
        } else {
            String qtyText = txtQty.getText().trim();
            int qty = qtyText.isEmpty() ? 1 : Integer.parseInt(qtyText);
            if (editingCountable != null) {
                editingCountable.getType().set(typeName);
                editingCountable.getBrand().set(brandName);
                editingCountable.getModel().set(modelName);
                editingCountable.setTypeId(type.getId());
                editingCountable.setBrandId(brand.getId());
                editingCountable.setModelId(model.getId());
                editingCountable.getQuantity().set(qty);
                editingCountable.getObservations().set(obs);
                editingCountable.setModifiesStock(modifiesStock);
                editingCountable.setModifiesStockReason(modifiesStockReason);
            } else {
                CountableItem newCountable = new CountableItem(typeName, brandName, modelName, qty, obs,
                    type.getId(), brand.getId(), model.getId(), modifiesStock);
                newCountable.setModifiesStockReason(modifiesStockReason);
                parentController.addCountable(newCountable);
            }
        }

        closeDialog();
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    private void closeDialog() {
        ((Stage) btnSave.getScene().getWindow()).close();
    }
}
