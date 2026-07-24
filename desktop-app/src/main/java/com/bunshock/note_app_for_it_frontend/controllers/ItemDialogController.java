package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.SnValidation;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.IHistoryService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.animation.FadeTransition;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
    @FXML private javafx.scene.text.TextFlow flowAfPattern;

    @FXML private VBox containerCountableFields;
    @FXML private TextField txtQty;

    @FXML private HBox hboxGlpiStatus;
    @FXML private ProgressIndicator progressGlpi;
    @FXML private Label lblGlpiStatus;

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

    private int pinnedTypeCount;
    private int pinnedBrandCount;
    private int pinnedModelCount;

    private static final int OBSERVATIONS_MAX_LENGTH = 200;

    public void initialize() {
        equipmentService = ServiceLocator.getInstance().getEquipmentService();
        historyService = ServiceLocator.getInstance().getHistoryService();
        txtObs.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= OBSERVATIONS_MAX_LENGTH ? change : null));
        txtQty.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.isEmpty()) return change;
            if (newText.matches("[1-9][0-9]{0,2}")) return change;
            return null;
        }));

        List<String> mostUsedTypes = historyService.getMostUsedTypeNames(
            MOST_USED_WINDOW_DAYS, MOST_USED_MIN_USES, MOST_USED_LIMIT);
        cmbType.setItems(FXCollections.observableArrayList(reorderWithPinned(
            equipmentService.getAllTypes(), mostUsedTypes, EquipmentType::getName,
            count -> pinnedTypeCount = count)));
        applyGenericCellFactory(cmbType, () -> pinnedTypeCount);
        applyGenericCellFactory(cmbBrand, () -> pinnedBrandCount);
        applyGenericCellFactory(cmbModel, () -> pinnedModelCount);

        cmbType.valueProperty().addListener((obs, old, type) -> onTypeSelected(type));
        cmbBrand.valueProperty().addListener((obs, old, brand) -> onBrandSelected(brand));
        cmbModel.valueProperty().addListener((obs, old, model) -> onModelSelected(model));

        txtSerial.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) applySnFormatting();
        });

        txtSerial.textProperty().addListener((obs, old, val) -> validateSnLength());

        txtAF.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) applyAfFormatting();
        });

        txtAF.textProperty().addListener((obs, old, val) -> refreshAfFlow(val));

        AppConfig.AfFormat fmt = ConfigService.getInstance().getConfig().afFormat;
        Pattern afCharPattern = Pattern.compile(fmt.inputPattern != null ? fmt.inputPattern : "\\d");
        txtAF.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.length() <= fmt.length &&
                    newText.chars().allMatch(c -> afCharPattern.matcher(String.valueOf((char) c)).matches())) {
                return change;
            }
            return null;
        }));


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

    private void updateSinSnForType(EquipmentType type) {
        if (type == null || !type.isAsset()) return;
        boolean requiresSerial = type.isRequiresSerial();
        chkSinSN.setDisable(requiresSerial);
        if (requiresSerial) {
            chkSinSN.setSelected(false);
            txtSerial.setDisable(false);
            txtSerial.setPromptText("Ingrese S/N...");
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
    }

    @FXML
    private void handleAfToggle() {
        boolean enabled = chkEnableAF.isSelected();
        txtAF.setDisable(!enabled);
        if (!enabled) {
            txtAF.clear();
            txtAF.setStyle("");
            txtAF.setPromptText("Deshabilitado");
            flowAfPattern.getChildren().clear();
        } else {
            txtAF.setPromptText("Ingrese A/F...");
            refreshAfFlow(null);
        }
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
            patternOk = sn.matches(regex);
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

    private void applyAfFormatting() {
        if (!chkEnableAF.isSelected()) return;
        String raw = txtAF.getText().trim();
        if (raw.isEmpty()) return;
        txtAF.setText(formatAF(raw));
    }

    private void refreshAfFlow(String rawInput) {
        AppConfig.AfFormat fmt = ConfigService.getInstance().getConfig().afFormat;
        flowAfPattern.getChildren().clear();
        String desc = afTypeDescription(fmt.inputPattern, fmt.length);
        String placeholder = afPlaceholder(fmt.inputPattern).repeat(fmt.length);
        javafx.scene.text.Text descNode = new javafx.scene.text.Text(desc + ". Ej: ");
        descNode.setStyle("-fx-fill: #64748b; -fx-font-size: 10;");
        javafx.scene.text.Text fixedNode = new javafx.scene.text.Text(fmt.prefix + fmt.separator);
        fixedNode.setStyle("-fx-fill: #334155; -fx-font-size: 10; -fx-font-weight: bold;");
        javafx.scene.text.Text varNode = new javafx.scene.text.Text(placeholder);
        varNode.setStyle("-fx-fill: #94a3b8; -fx-font-size: 10; -fx-font-style: italic;");
        flowAfPattern.getChildren().addAll(descNode, fixedNode, varNode);
        if (rawInput != null && !rawInput.isBlank()) {
            javafx.scene.text.Text resultNode = new javafx.scene.text.Text("  →  " + formatAF(rawInput.trim()));
            resultNode.setStyle("-fx-fill: #64748b; -fx-font-size: 10;");
            flowAfPattern.getChildren().add(resultNode);
        }
    }

    private String formatAF(String raw) {
        AppConfig.AfFormat fmt = ConfigService.getInstance().getConfig().afFormat;
        String padded = fmt.filler.repeat(Math.max(0, fmt.length - raw.length())) + raw;
        if (padded.length() > fmt.length) padded = padded.substring(padded.length() - fmt.length);
        return fmt.prefix + fmt.separator + padded;
    }

    private String extractAfRaw(String formattedAF) {
        AppConfig.AfFormat fmt = ConfigService.getInstance().getConfig().afFormat;
        String head = fmt.prefix + fmt.separator;
        return formattedAF.startsWith(head) ? formattedAF.substring(head.length()) : formattedAF;
    }

    private String afTypeDescription(String inputPattern, int length) {
        String p = inputPattern != null ? inputPattern.trim() : "\\d";
        boolean hasDigits  = p.equals("\\d") || p.contains("0-9");
        boolean hasUpper   = p.contains("A-Z");
        boolean hasLower   = p.contains("a-z");
        boolean hasLetters = hasUpper || hasLower;
        String unit = hasDigits && !hasLetters ? "dígitos" : "caracteres";
        String type;
        if (hasDigits && hasLetters) type = "Letras y números";
        else if (hasUpper)           type = "Solo letras mayúsculas";
        else if (hasLower)           type = "Solo letras minúsculas";
        else                         type = "Solo números";
        return type + ", hasta " + length + " " + unit;
    }

    private String afPlaceholder(String inputPattern) {
        if (inputPattern == null) return "0";
        String p = inputPattern.trim();
        if (p.equals("\\d") || p.equals("[0-9]")) return "0";
        if (p.equals("[A-Z]") || p.equals("[a-z]")) return "A";
        if (p.contains("A-Z") && p.contains("0-9")) return "X";
        if (p.contains("A-Z")) return "A";
        if (p.contains("0-9")) return "0";
        return "X";
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

        String af = asset.getAf().get();
        if (af != null && !af.isEmpty()) {
            chkEnableAF.setSelected(true);
            handleAfToggle();
            txtAF.setText(extractAfRaw(af));
        }

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

        if (type != null && type.isAsset() && !chkSinSN.isSelected()) {
            if (txtSerial.getText().trim().isEmpty()) {
                txtSerial.setStyle("-fx-border-color: #ef4444; -fx-border-width: 1.5; -fx-border-radius: 4; -fx-background-radius: 4;");
                valid = false;
            } else {
                txtSerial.setStyle("");
            }
        }

        if (type != null && type.isAsset() && chkEnableAF.isSelected()) {
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

        if (type.isAsset()) {
            String sn = chkSinSN.isSelected() ? "" : txtSerial.getText().trim();
            if (!chkSinSN.isSelected() && chkUppercase.isSelected()) sn = sn.toUpperCase();
            String afRaw = txtAF.getText().trim();
        String af = chkEnableAF.isSelected() && !afRaw.isEmpty() ? formatAF(afRaw) : "";

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
            } else {
                parentController.addAsset(new AssetItem(typeName, brandName, modelName, obs, sn, af,
                    type.getId(), brand.getId(), model.getId()));
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
            } else {
                parentController.addCountable(new CountableItem(typeName, brandName, modelName, qty, obs,
                    type.getId(), brand.getId(), model.getId()));
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
