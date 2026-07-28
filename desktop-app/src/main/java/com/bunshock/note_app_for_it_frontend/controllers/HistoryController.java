package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.DatePicker;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

public class HistoryController {

    @FXML private DatePicker   dpFrom;
    @FXML private DatePicker   dpTo;
    @FXML private MenuButton   mnuProfileType;
    @FXML private MenuButton   mnuGlpiStatus;
    @FXML private TextField    txtRecipientSearch;
    @FXML private TextField    txtAuthorSearch;
    @FXML private MenuButton   mnuItemType;
    @FXML private MenuButton   mnuItemBrand;
    @FXML private MenuButton   mnuItemModel;
    @FXML private MenuButton   mnuSede;

    @FXML private TableView<NoteReport>           tblGlobal;
    @FXML private TableColumn<NoteReport, String> colGDate;
    @FXML private TableColumn<NoteReport, String> colGProfile;
    @FXML private TableColumn<NoteReport, String> colGRecipient;
    @FXML private TableColumn<NoteReport, String> colGAuthor;
    @FXML private TableColumn<NoteReport, String> colGItems;
    @FXML private TableColumn<NoteReport, String> colGStatus;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final List<String> PROFILE_TYPE_OPTIONS = List.of(
        "Entrega", "Devolución", "Fin de Contrato", "Entrega - Proveedor", "Préstamo");

    private static final List<String> GLPI_STATUS_LABELS = List.of(
        "Pendiente", "Sincronizado", "Rechazado", "Sin GLPI");

    private static final Map<String, String> GLPI_LABEL_TO_CODE = Map.of(
        "Pendiente",    "PENDING",
        "Sincronizado", "SYNCED",
        "Rechazado",    "REJECTED",
        "Sin GLPI",     "N_A");

    // NOTE_REPORT.profile_type is stored raw (see CLAUDE.md), and its raw form differs by how
    // the row was created: live "Generar Nota" stores the ALL-CAPS ToggleButton text, but
    // DatabaseService.seedHistoryData()'s demo rows use the nice-cased label directly — so each
    // filter label must match both forms, not just the one the live UI currently produces.
    private static final Map<String, List<String>> PROFILE_TYPE_LABEL_TO_RAW = Map.of(
        "Entrega",             List.of("ENTREGA", "Entrega"),
        "Devolución",          List.of("DEVOLUCIÓN", "Devolución"),
        "Fin de Contrato",     List.of("ENTREGA PERMANENTE", "FIN DE CONTRATO", "Fin de Contrato"),
        "Préstamo",            List.of("PRÉSTAMO", "Préstamo"),
        "Entrega - Proveedor", List.of("ENTREGA - PROVEEDOR", "Entrega - Proveedor"));

    private final Set<String> selProfileTypes = new LinkedHashSet<>();
    private final Set<String> selGlpiStatuses = new LinkedHashSet<>();
    private final Set<String> selItemTypes    = new LinkedHashSet<>();
    private final Set<String> selItemBrands   = new LinkedHashSet<>();
    private final Set<String> selItemModels   = new LinkedHashSet<>();
    private final Set<String> selSedes        = new LinkedHashSet<>();

    private boolean suppressCallbacks = false;

    public void initialize() {
        setupTable();
        initStaticMenus();
        initEquipmentMenus();
        loadGlobal(new HistoryFilter());
    }

    // ── Filter menus ──────────────────────────────────────────────────────────

    private void initStaticMenus() {
        populateMenu(mnuProfileType, PROFILE_TYPE_OPTIONS, selProfileTypes, this::autoSearch);
        populateMenu(mnuGlpiStatus, GLPI_STATUS_LABELS, selGlpiStatuses, this::autoSearch);
    }

    private void initEquipmentMenus() {
        var svc = ServiceLocator.getInstance().getHistoryService();
        populateMenu(mnuItemType,  svc.getDistinctItemTypes(),           selItemTypes,  this::onItemTypeChanged);
        populateMenu(mnuItemBrand, svc.getDistinctItemBrands(null),      selItemBrands, this::onItemBrandChanged);
        populateMenu(mnuItemModel, svc.getDistinctItemModels(null, null), selItemModels, this::autoSearch);
        populateMenu(mnuSede,      svc.getDistinctSedes(),               selSedes,      this::autoSearch);
    }

    private void onItemTypeChanged() {
        refreshBrandMenu();
        autoSearch();
    }

    private void onItemBrandChanged() {
        refreshModelMenu();
        autoSearch();
    }

    private void refreshBrandMenu() {
        var svc = ServiceLocator.getInstance().getHistoryService();
        List<String> types = new ArrayList<>(selItemTypes);
        selItemBrands.clear();
        List<String> brands = svc.getDistinctItemBrands(types.isEmpty() ? null : types);
        populateMenu(mnuItemBrand, brands, selItemBrands, this::onItemBrandChanged);
        refreshModelMenu();
    }

    private void refreshModelMenu() {
        var svc = ServiceLocator.getInstance().getHistoryService();
        List<String> types  = new ArrayList<>(selItemTypes);
        List<String> brands = new ArrayList<>(selItemBrands);
        selItemModels.clear();
        List<String> models = svc.getDistinctItemModels(
            types.isEmpty()  ? null : types,
            brands.isEmpty() ? null : brands);
        populateMenu(mnuItemModel, models, selItemModels, this::autoSearch);
    }

    private void populateMenu(MenuButton btn, List<String> options, Set<String> selected, Runnable onChange) {
        btn.getItems().clear();

        CheckBox todasChk = new CheckBox("Todas");
        todasChk.setSelected(selected.isEmpty());
        todasChk.getStyleClass().add("menu-filter-checkbox");
        btn.getItems().add(new CustomMenuItem(todasChk, false));
        btn.getItems().add(new SeparatorMenuItem());

        boolean[] lock = {false};

        for (String opt : options) {
            CheckBox chk = new CheckBox(opt);
            chk.setSelected(selected.contains(opt));
            chk.getStyleClass().add("menu-filter-checkbox");
            btn.getItems().add(new CustomMenuItem(chk, false));

            chk.selectedProperty().addListener((obs, old, newVal) -> {
                if (lock[0] || suppressCallbacks) return;
                lock[0] = true;
                if (newVal) {
                    selected.add(opt);
                    todasChk.setSelected(false);
                } else {
                    selected.remove(opt);
                    if (selected.isEmpty()) todasChk.setSelected(true);
                }
                lock[0] = false;
                updateMenuLabel(btn, selected);
                if (!suppressCallbacks) onChange.run();
            });
        }

        todasChk.selectedProperty().addListener((obs, old, newVal) -> {
            if (lock[0] || suppressCallbacks || !newVal) return;
            lock[0] = true;
            selected.clear();
            btn.getItems().stream()
                .filter(it -> it instanceof CustomMenuItem)
                .map(it -> ((CustomMenuItem) it).getContent())
                .filter(n -> n instanceof CheckBox && n != todasChk)
                .forEach(n -> ((CheckBox) n).setSelected(false));
            lock[0] = false;
            updateMenuLabel(btn, selected);
            if (!suppressCallbacks) onChange.run();
        });

        updateMenuLabel(btn, selected);
    }

    private void updateMenuLabel(MenuButton btn, Set<String> selected) {
        if (selected.isEmpty()) {
            btn.setText("Todas");
        } else if (selected.size() == 1) {
            btn.setText(selected.iterator().next());
        } else {
            btn.setText(selected.size() + " seleccionados");
        }
    }

    // ── Filter actions ────────────────────────────────────────────────────────

    @FXML
    private void handleSearch() {
        loadGlobal(buildFilter());
    }

    /** Reloads the table with whatever filters are currently set (does not reset them) — called
     * by MainController every time the History section is opened, so newly generated notes show
     * up without the technician needing to click "Buscar" or leaving previously-applied filters. */
    public void refresh() {
        loadGlobal(buildFilter());
    }

    @FXML
    private void handleClearFilters() {
        suppressCallbacks = true;
        dpFrom.setValue(null);
        dpTo.setValue(null);
        txtRecipientSearch.clear();
        txtAuthorSearch.clear();
        selProfileTypes.clear();
        selGlpiStatuses.clear();
        selItemTypes.clear();
        selItemBrands.clear();
        selItemModels.clear();
        selSedes.clear();
        suppressCallbacks = false;
        populateMenu(mnuProfileType, PROFILE_TYPE_OPTIONS, selProfileTypes, this::autoSearch);
        populateMenu(mnuGlpiStatus, GLPI_STATUS_LABELS, selGlpiStatuses, this::autoSearch);
        initEquipmentMenus();
        loadGlobal(new HistoryFilter());
    }

    private void autoSearch() {
        loadGlobal(buildFilter());
    }

    private HistoryFilter buildFilter() {
        HistoryFilter f = new HistoryFilter();
        f.setFromDate(dpFrom.getValue());
        f.setToDate(dpTo.getValue());
        if (!selProfileTypes.isEmpty()) f.setProfileTypes(expandProfileTypeLabels(selProfileTypes));
        if (!selGlpiStatuses.isEmpty()) {
            f.setGlpiStatuses(selGlpiStatuses.stream()
                .map(GLPI_LABEL_TO_CODE::get)
                .filter(c -> c != null)
                .toList());
        }
        String recipient = txtRecipientSearch.getText();
        if (recipient != null && !recipient.isBlank()) f.setRecipientSearch(recipient);
        String author = txtAuthorSearch.getText();
        if (author != null && !author.isBlank()) f.setAuthorSearch(author);
        if (!selItemTypes.isEmpty())  f.setItemTypes(new ArrayList<>(selItemTypes));
        if (!selItemBrands.isEmpty()) f.setItemBrands(new ArrayList<>(selItemBrands));
        if (!selItemModels.isEmpty()) f.setItemModels(new ArrayList<>(selItemModels));
        if (!selSedes.isEmpty())      f.setSedes(new ArrayList<>(selSedes));
        return f;
    }

    private static List<String> expandProfileTypeLabels(Set<String> labels) {
        return labels.stream()
            .flatMap(label -> PROFILE_TYPE_LABEL_TO_RAW.getOrDefault(label, List.of(label)).stream())
            .toList();
    }

    // ── Table setup ───────────────────────────────────────────────────────────

    private void setupTable() {
        colGDate.setSortable(false);
        colGProfile.setSortable(false);
        colGRecipient.setSortable(false);
        colGAuthor.setSortable(false);
        colGItems.setSortable(false);
        colGStatus.setSortable(false);

        colGDate.setCellValueFactory(d ->
            new SimpleStringProperty(d.getValue().getCreatedAt().format(FMT)));
        colGProfile.setCellValueFactory(d ->
            new SimpleStringProperty(toDisplayName(d.getValue().getProfileType())));
        colGRecipient.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getRecipientDisplay())));
        colGAuthor.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getAuthorName())));
        colGItems.setCellValueFactory(d -> {
            NoteReport r = d.getValue();
            // getItems() isn't populated on these summary rows (mapSummary() only loads the
            // aggregate counts below, not the full item list — that's loaded separately when
            // opening a note's detail popup), so this must read the SQL-aggregated counts
            // directly rather than deriving countables from items.size() - assets, which always
            // evaluated to <= 0 here and silently hid every countable-only or mixed note.
            int assets = r.getAssetItemCount();
            int countables = r.getCountableItemCount();
            String label = assets > 0
                ? assets + "A" + (countables > 0 ? " / " + countables + "C" : "")
                : (countables > 0 ? countables + "C" : "—");
            return new SimpleStringProperty(label);
        });
        colGStatus.setCellValueFactory(d -> new SimpleStringProperty(glpiStatusLabel(d.getValue())));

        tblGlobal.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(NoteReport report, boolean empty) {
                super.updateItem(report, empty);
                setStyle(empty || report == null ? "" : computeRowStyle(report));
            }
        });

        tblGlobal.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                NoteReport selected = tblGlobal.getSelectionModel().getSelectedItem();
                if (selected != null) openDetail(selected.getId());
            }
        });
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadGlobal(HistoryFilter filter) {
        List<NoteReport> reports = ServiceLocator.getInstance().getHistoryService().getFiltered(filter);
        tblGlobal.setItems(FXCollections.observableArrayList(reports));
    }

    // ── Row color ─────────────────────────────────────────────────────────────

    private String computeRowStyle(NoteReport r) {
        int pending  = r.getPendingItemCount();
        int synced   = r.getSyncedItemCount();
        int rejected = r.getRejectedItemCount();
        // GLPI-tracked total, not raw asset count — a Préstamo note's assets are all glpi_status
        // N_A (see CLAUDE.md's "Préstamo assets are deliberately excluded from GLPI sync"), so
        // getAssetItemCount() alone would count them without any of pending/synced/rejected ever
        // summing back up to it, falling through to a broken zero-stop gradient below instead of
        // this flat "nothing to track" color.
        int total = pending + synced + rejected;
        if (total == 0) return "-fx-background-color: #f1f5f9;";

        if (pending  == total) return "-fx-background-color: rgba(251,146,60,0.18);";
        if (synced   == total) return "-fx-background-color: rgba(34,197,94,0.18);";
        if (rejected == total) return "-fx-background-color: rgba(239,68,68,0.18);";

        StringBuilder g = new StringBuilder("-fx-background-color: linear-gradient(to right");
        double pos = 0;
        pos = appendSlice(g, rejected, total, pos, "239,68,68");
        pos = appendSlice(g, pending,  total, pos, "251,146,60");
        appendSlice(g, synced, total, pos, "34,197,94");
        g.append(");");
        return g.toString();
    }

    private double appendSlice(StringBuilder g, int count, int total, double pos, String rgb) {
        if (count <= 0) return pos;
        double end = pos + (double) count / total * 100;
        g.append(String.format(Locale.ROOT, ", rgba(%s,0.25) %.2f%%", rgb, pos));
        g.append(String.format(Locale.ROOT, ", rgba(%s,0.25) %.2f%%", rgb, end));
        return end;
    }

    private String glpiStatusLabel(NoteReport r) {
        int pending  = r.getPendingItemCount();
        int synced   = r.getSyncedItemCount();
        int rejected = r.getRejectedItemCount();
        // GLPI-tracked total, not raw asset count — see computeRowStyle()'s matching comment.
        int total = pending + synced + rejected;
        if (total == 0) return "—";
        if (pending  == total) return "Pendiente";
        if (synced   == total) return "Sincronizado";
        if (rejected == total) return "Rechazado";
        return "Mixto";
    }

    // ── Detail popup ──────────────────────────────────────────────────────────

    private void openDetail(int reportId) {
        NoteReport full = ServiceLocator.getInstance().getHistoryService().getById(reportId);
        if (full == null) return;
        try {
            NoteDetailController.open(full, AdminSession.getInstance().isActive(),
                tblGlobal.getScene().getWindow(),
                () -> loadGlobal(buildFilter()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    @FXML
    private void handleExportCsv() {
        List<NoteReport> data = new ArrayList<>(tblGlobal.getItems());
        if (data.isEmpty()) { showInfo("No hay datos para exportar."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar como CSV");
        fc.setInitialFileName("historial.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = fc.showSaveDialog(tblGlobal.getScene().getWindow());
        if (file == null) return;

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            pw.print('﻿');
            pw.println("Fecha,Tipo,Destinatario,Autor,Estado GLPI");
            for (NoteReport r : data) {
                pw.println(String.join(",",
                    csvEscape(r.getCreatedAt().format(FMT)),
                    csvEscape(toDisplayName(r.getProfileType())),
                    csvEscape(orEmpty(r.getRecipientDisplay())),
                    csvEscape(orEmpty(r.getAuthorName())),
                    csvEscape(glpiStatusLabel(r))));
            }
        } catch (IOException e) {
            showError("No se pudo guardar el archivo: " + e.getMessage());
        }
    }

    @FXML
    private void handleExportXlsx() {
        List<NoteReport> data = new ArrayList<>(tblGlobal.getItems());
        if (data.isEmpty()) { showInfo("No hay datos para exportar."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar como Excel");
        fc.setInitialFileName("historial.xlsx");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel", "*.xlsx"));
        File file = fc.showSaveDialog(tblGlobal.getScene().getWindow());
        if (file == null) return;

        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(file)) {

            Sheet sheet = wb.createSheet("Historial");
            String[] headers = {"Fecha", "Tipo", "Destinatario", "Autor", "Estado GLPI"};

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            for (int i = 0; i < data.size(); i++) {
                NoteReport r = data.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(r.getCreatedAt().format(FMT));
                row.createCell(1).setCellValue(toDisplayName(r.getProfileType()));
                row.createCell(2).setCellValue(orEmpty(r.getRecipientDisplay()));
                row.createCell(3).setCellValue(orEmpty(r.getAuthorName()));
                row.createCell(4).setCellValue(glpiStatusLabel(r));
            }

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            wb.write(fos);
        } catch (IOException e) {
            showError("No se pudo guardar el archivo: " + e.getMessage());
        }
    }

    private String csvEscape(String s) {
        if (s == null) s = "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Exportar"); a.setHeaderText(null); a.setContentText(msg); a.showAndWait();
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Error al exportar"); a.setHeaderText(null); a.setContentText(msg); a.showAndWait();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String orEmpty(String s) { return s != null ? s : ""; }

    // Mirrors NoteGenerationService.toDisplayName() — duplicated per the no-shared-abstraction
    // convention, since it's only used to format a value already read from the DB here.
    private static String toDisplayName(String profileType) {
        if (profileType == null) return "";
        return switch (profileType.toUpperCase().trim()) {
            case "ENTREGA"             -> "Entrega";
            case "DEVOLUCIÓN"          -> "Devolución";
            case "DEVOLUCION"          -> "Devolución";
            case "PRÉSTAMO"            -> "Préstamo";
            case "PRESTAMO"            -> "Préstamo";
            case "ENTREGA PERMANENTE"  -> "Fin de contrato";
            case "FIN DE CONTRATO"     -> "Fin de contrato";
            case "ENTREGA - PROVEEDOR" -> "Entrega - Proveedor";
            default                    -> profileType;
        };
    }
}
