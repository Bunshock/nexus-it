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

import com.bunshock.note_app_for_it_frontend.models.history.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.stage.FileChooser;

public class HistoryController {

    @FXML private DatePicker   dpFrom;
    @FXML private DatePicker   dpTo;
    @FXML private MenuButton   mnuProfileType;
    @FXML private MenuButton   mnuGlpiStatus;
    @FXML private MenuButton   mnuApprovalStatus;
    @FXML private TextField    txtRecipientSearch;
    @FXML private TextField    txtAuthorSearch;
    @FXML private MenuButton   mnuItemType;
    @FXML private MenuButton   mnuItemBrand;
    @FXML private MenuButton   mnuItemModel;
    @FXML private MenuButton   mnuSede;
    @FXML private Label        lblPendingApproval;
    @FXML private Label        lblPendingGlpi;

    @FXML private TableView<NoteReport>           tblGlobal;
    @FXML private TableColumn<NoteReport, String> colGApproval;
    @FXML private TableColumn<NoteReport, String> colGDate;
    @FXML private TableColumn<NoteReport, String> colGProfile;
    @FXML private TableColumn<NoteReport, String> colGRecipient;
    @FXML private TableColumn<NoteReport, String> colGAuthor;
    @FXML private TableColumn<NoteReport, String> colGSede;
    @FXML private TableColumn<NoteReport, String> colGMotivo;
    @FXML private TableColumn<NoteReport, String> colGItems;
    @FXML private TableColumn<NoteReport, String> colGStatus;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final List<String> PROFILE_TYPE_OPTIONS = List.of(
        "Entrega", "Devolución", "Entrega Permanente", "Entrega - Proveedor", "Préstamo", "Remito de Envío");

    private static final List<String> GLPI_STATUS_LABELS = List.of(
        "Pendiente", "Sincronizado", "Rechazado", "Sin GLPI");

    private static final Map<String, String> GLPI_LABEL_TO_CODE = Map.of(
        "Pendiente",    "PENDING",
        "Sincronizado", "SYNCED",
        "Rechazado",    "REJECTED",
        "Sin GLPI",     "N_A");

    private static final List<String> APPROVAL_STATUS_OPTIONS = List.of("Pendiente", "Aprobada", "Rechazada");

    // NOTE_REPORT.profile_type is stored raw (see CLAUDE.md), and its raw form differs by how
    // the row was created: live "Generar Nota" stores the ALL-CAPS ToggleButton text, but
    // DatabaseService.seedHistoryData()'s demo rows use the nice-cased label directly — so each
    // filter label must match both forms, not just the one the live UI currently produces.
    private static final Map<String, List<String>> PROFILE_TYPE_LABEL_TO_RAW = Map.of(
        "Entrega",             List.of("ENTREGA", "Entrega"),
        "Devolución",          List.of("DEVOLUCIÓN", "Devolución"),
        "Entrega Permanente",  List.of("ENTREGA PERMANENTE", "FIN DE CONTRATO", "Fin de Contrato"),
        "Préstamo",            List.of("PRÉSTAMO", "Préstamo"),
        "Entrega - Proveedor", List.of("ENTREGA - PROVEEDOR", "Entrega - Proveedor"),
        "Remito de Envío",     List.of("REMITO DE ENVÍO", "Remito de Envío"));

    private final Set<String> selProfileTypes     = new LinkedHashSet<>();
    private final Set<String> selGlpiStatuses     = new LinkedHashSet<>();
    private final Set<String> selApprovalStatuses = new LinkedHashSet<>();
    private final Set<String> selItemTypes        = new LinkedHashSet<>();
    private final Set<String> selItemBrands       = new LinkedHashSet<>();
    private final Set<String> selItemModels       = new LinkedHashSet<>();
    private final Set<String> selSedes            = new LinkedHashSet<>();

    private boolean suppressCallbacks = false;

    // Neither field is ever persisted — both are query-only, feeding HistoryFilter's
    // authorSearch/recipientSearch LIKE clauses (see buildFilter()) — so there's no DB column
    // bound to match. Capped purely as a sanity guard against an accidental huge paste.
    private static final int SEARCH_MAX_LENGTH = 255;

    public void initialize() {
        setupTable();
        resetApprovalStatusFilterToDefault();
        initStaticMenus();
        resetSedeFilterToDefault();
        initEquipmentMenus();
        txtAuthorSearch.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SEARCH_MAX_LENGTH ? change : null));
        txtRecipientSearch.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SEARCH_MAX_LENGTH ? change : null));
        loadGlobal(new HistoryFilter());
    }

    // Defaults to the technician's own Sede (if any), not "Todas" — a technician mostly cares
    // about their own site's history. Just a starting point; can be cleared/changed freely.
    private void resetSedeFilterToDefault() {
        selSedes.clear();
        String mySede = TechnicianSessionService.getInstance().getSede();
        if (mySede != null && !mySede.isBlank()) selSedes.add(mySede);
    }

    // Defaults to Pendiente+Aprobada, not "Todas" — a RECHAZADO note is void and stays hidden
    // from the default view. Unchecking to "Todas" (or picking Rechazada) shows everything.
    private void resetApprovalStatusFilterToDefault() {
        selApprovalStatuses.clear();
        selApprovalStatuses.add("Pendiente");
        selApprovalStatuses.add("Aprobada");
    }

    private String approvalStatusToRaw(String label) {
        return switch (label) {
            case "Aprobada" -> "APPROVED";
            case "Rechazada" -> "REJECTED";
            default -> "PENDING";
        };
    }

    // ── Filter menus ──────────────────────────────────────────────────────────

    private void initStaticMenus() {
        populateMenu(mnuProfileType, PROFILE_TYPE_OPTIONS, selProfileTypes, this::autoSearch);
        populateMenu(mnuGlpiStatus, GLPI_STATUS_LABELS, selGlpiStatuses, this::autoSearch);
        populateMenu(mnuApprovalStatus, APPROVAL_STATUS_OPTIONS, selApprovalStatuses, this::autoSearch);
    }

    private void initEquipmentMenus() {
        var svc = ServiceLocator.getInstance().getHistoryService();
        populateMenu(mnuItemType,  svc.getDistinctItemTypes(),           selItemTypes,  this::onItemTypeChanged);
        populateMenu(mnuItemBrand, svc.getDistinctItemBrands(null),      selItemBrands, this::onItemBrandChanged);
        populateMenu(mnuItemModel, svc.getDistinctItemModels(null, null), selItemModels, this::autoSearch);
        populateMenu(mnuSede,      sedeCatalogNames(),                  selSedes,      this::autoSearch);
    }

    // Sede options come from the live SEDE catalog, not "values actually seen in history" like
    // the item type/brand/model filters — a technician's own Sede may have no history yet at all
    // (e.g. a brand-new site) and must still be selectable.
    private List<String> sedeCatalogNames() {
        return ServiceLocator.getInstance().getEquipmentService().getAllSedes().stream()
            .map(com.bunshock.note_app_for_it_frontend.models.catalog.Sede::getName)
            .toList();
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
            if (lock[0] || suppressCallbacks) return;
            if (!newVal) {
                // "Todas" can only go true->false when nothing else is selected (an individual
                // checkbox being checked already un-checks it directly, under lock, above). Snap
                // it back instead of leaving nothing visibly selected while the filter still
                // matches everything.
                lock[0] = true;
                todasChk.setSelected(true);
                lock[0] = false;
                return;
            }
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
        resetSedeFilterToDefault();
        resetApprovalStatusFilterToDefault();
        suppressCallbacks = false;
        populateMenu(mnuProfileType, PROFILE_TYPE_OPTIONS, selProfileTypes, this::autoSearch);
        populateMenu(mnuGlpiStatus, GLPI_STATUS_LABELS, selGlpiStatuses, this::autoSearch);
        populateMenu(mnuApprovalStatus, APPROVAL_STATUS_OPTIONS, selApprovalStatuses, this::autoSearch);
        initEquipmentMenus();
        // Goes through buildFilter() (not a bare `new HistoryFilter()`) so the PENDING+APPROVED
        // default still applies after clearing — "Limpiar filtros" resets to the default view,
        // it doesn't newly reveal RECHAZADO notes.
        loadGlobal(buildFilter());
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
        // Default selection is Pendiente+Aprobada (see resetApprovalStatusFilterToDefault()) —
        // RECHAZADO notes are void and stay hidden from the default view. Unchecking down to
        // "Todas" (empty set) applies no filter at all; explicitly picking Rechazada shows it.
        if (!selApprovalStatuses.isEmpty()) {
            f.setApprovalStatuses(selApprovalStatuses.stream().map(this::approvalStatusToRaw).toList());
        }
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
        colGSede.setSortable(false);
        colGMotivo.setSortable(false);
        colGItems.setSortable(false);
        colGStatus.setSortable(false);

        // A dedicated narrow column, not a row border — a border consumes layout space and shifts
        // cell content relative to the headers; a column's own header reserves the same width
        // automatically. See approvalStatusColor() below.
        colGApproval.setCellValueFactory(d -> new SimpleStringProperty(""));
        colGApproval.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                NoteReport r = empty || getTableRow() == null ? null : getTableRow().getItem();
                setStyle(r == null ? "" : "-fx-background-color: " + approvalStatusColor(r) + "; -fx-padding: 0;");
            }
        });

        colGDate.setCellValueFactory(d ->
            new SimpleStringProperty(d.getValue().getCreatedAt().format(FMT)));
        colGProfile.setCellValueFactory(d ->
            new SimpleStringProperty(toDisplayName(d.getValue().getProfileType())));
        colGRecipient.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getRecipientDisplay())));
        colGAuthor.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getAuthorName())));
        colGSede.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getSede())));
        colGMotivo.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getMotivo())));
        colGItems.setCellValueFactory(d -> {
            NoteReport r = d.getValue();
            // getItems() isn't populated on summary rows (only loaded when opening a note's
            // detail popup) — must read the SQL-aggregated counts directly, not derive from
            // items.size().
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
        updatePendingApprovalLabel(reports);
        updatePendingGlpiLabel(reports);
    }

    // Counts filtered rows still awaiting approval — red, distinct from the orange GLPI-pending
    // pill below. Hidden entirely at 0 (a present badge always means something needs attention).
    private void updatePendingApprovalLabel(List<NoteReport> reports) {
        long pending = reports.stream()
            .filter(r -> r.getApprovalStatus() == null || "PENDING".equals(r.getApprovalStatus()))
            .count();
        boolean show = pending > 0;
        lblPendingApproval.setText("⏳ " + pending + " pendientes de aprobación");
        lblPendingApproval.setVisible(show);
        lblPendingApproval.setManaged(show);
    }

    // Counts filtered rows with a pending GLPI item AND already APPROVED — a still-pending or
    // rejected note isn't a real sync candidate yet.
    private void updatePendingGlpiLabel(List<NoteReport> reports) {
        long pending = reports.stream()
            .filter(r -> "APPROVED".equals(r.getApprovalStatus()))
            .filter(r -> r.getPendingItemCount() > 0)
            .count();
        boolean show = pending > 0;
        lblPendingGlpi.setText("⏳ " + pending + " con sincronización GLPI pendiente");
        lblPendingGlpi.setVisible(show);
        lblPendingGlpi.setManaged(show);
    }

    // ── Row color ─────────────────────────────────────────────────────────────

    // A Préstamo note's items are never GLPI-tracked, so the GLPI-based coloring below would
    // always show flat gray — delegates to return-status coloring instead (duplicated from
    // PrestamoHistoryController.computeRowStyle()).
    private String computeRowStyle(NoteReport r) {
        if (isPrestamoProfileType(r.getProfileType())) {
            return computeRowStyleForPrestamo(r);
        }

        int pending  = r.getPendingItemCount();
        int synced   = r.getSyncedItemCount();
        int rejected = r.getRejectedItemCount();
        // GLPI-tracked total, not raw asset count — see the Préstamo note above for why a
        // Préstamo row never reaches this branch at all.
        int total = pending + synced + rejected;

        String background;
        if (total == 0) {
            background = "-fx-background-color: #f1f5f9;";
        } else if (pending == total) {
            background = "-fx-background-color: rgba(251,146,60,0.18);";
        } else if (synced == total) {
            background = "-fx-background-color: rgba(34,197,94,0.18);";
        } else if (rejected == total) {
            background = "-fx-background-color: rgba(239,68,68,0.18);";
        } else {
            StringBuilder g = new StringBuilder("-fx-background-color: linear-gradient(to right");
            double pos = 0;
            pos = appendSlice(g, rejected, total, pos, "239,68,68");
            pos = appendSlice(g, pending,  total, pos, "251,146,60");
            appendSlice(g, synced, total, pos, "34,197,94");
            g.append(");");
            background = g.toString();
        }

        return background;
    }

    private String computeRowStyleForPrestamo(NoteReport r) {
        int pending  = r.getReturnPendingItemCount();
        int returned = r.getReturnedItemCount();
        int lost     = r.getLostItemCount();
        int total = pending + returned + lost;

        if (total == 0) return "-fx-background-color: #f1f5f9;";
        if (pending  == total) return "-fx-background-color: rgba(251,146,60,0.18);";
        if (returned == total) return "-fx-background-color: rgba(34,197,94,0.18);";
        if (lost     == total) return "-fx-background-color: rgba(239,68,68,0.18);";

        StringBuilder g = new StringBuilder("-fx-background-color: linear-gradient(to right");
        double pos = 0;
        pos = appendSlice(g, lost, total, pos, "239,68,68");
        pos = appendSlice(g, pending, total, pos, "251,146,60");
        appendSlice(g, returned, total, pos, "34,197,94");
        g.append(");");
        return g.toString();
    }

    // Color for the dedicated colGApproval column — a column (not a border) so header and cell
    // width always match exactly.
    private String approvalStatusColor(NoteReport r) {
        String status = r.getApprovalStatus();
        if (status == null || "PENDING".equals(status)) return "#f97316";
        if ("REJECTED".equals(status)) return "#ef4444";
        return "#22c55e";
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

    // Same case/accent variants SqliteHistoryService.isPrestamo()/MainController's own copy
    // already tolerate — profile_type is stored raw (see CLAUDE.md), so detecting a Préstamo note
    // here has to match every form it's stored in, not just one casing.
    private static final List<String> PRESTAMO_PROFILE_TYPES = List.of("PRÉSTAMO", "PRESTAMO", "Préstamo");

    private static boolean isPrestamoProfileType(String profileType) {
        return profileType != null && PRESTAMO_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase);
    }

    // A Préstamo note opens PrestamoDetailController (same return-status rows/admin actions)
    // instead of NoteDetailController's GLPI-only view, which has no equivalent for Préstamo
    // return status at all.
    private void openDetail(int reportId) {
        NoteReport full = ServiceLocator.getInstance().getHistoryService().getById(reportId);
        if (full == null) return;
        try {
            if (isPrestamoProfileType(full.getProfileType())) {
                PrestamoDetailController.open(full, AdminSession.getInstance().isActive(),
                    tblGlobal.getScene().getWindow(),
                    () -> loadGlobal(buildFilter()));
            } else {
                NoteDetailController.open(full, AdminSession.getInstance().isActive(),
                    tblGlobal.getScene().getWindow(),
                    () -> loadGlobal(buildFilter()));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    // Every export column, in order — deliberately a superset of what's shown on screen. The
    // last 5 (Falla/CUIT/Responsable/Área-Evento/Observaciones) only apply to some profile
    // types; a blank cell for the rest is expected, not a bug.
    private static final String[] EXPORT_HEADERS = {
        "Fecha", "Autor", "Autor DNI", "Sede", "Tipo", "Motivo", "Destinatario",
        "Estado", "Razón Rechazo", "Estado GLPI",
        "Activos", "Contables", "Pend. GLPI", "Sync. GLPI", "Rech. GLPI",
        "Pend. Devol.", "Devueltos", "Perdidos",
        "Causa Falla", "Detalle Falla", "CUIT", "Responsable", "Responsable DNI",
        "Área/Evento", "Observaciones"
    };

    // Summary rows don't carry profile-specific detail fields (Falla, Provider CUIT/
    // responsible, Préstamo Área/Evento, Observaciones) — fetching the full report per row via
    // getById() is a deliberate, accepted trade-off (slower export) over leaving them blank.
    private List<String> exportRowValues(NoteReport summary) {
        NoteReport full = ServiceLocator.getInstance().getHistoryService().getById(summary.getId());
        if (full == null) full = summary;
        return List.of(
            summary.getCreatedAt().format(FMT),
            orEmpty(summary.getAuthorName()),
            orEmpty(summary.getAuthorDni()),
            orEmpty(summary.getSede()),
            toDisplayName(summary.getProfileType()),
            orEmpty(summary.getMotivo()),
            orEmpty(summary.getRecipientDisplay()),
            approvalStatusDisplay(summary.getApprovalStatus()),
            orEmpty(summary.getRejectionReason()),
            glpiStatusLabel(summary),
            String.valueOf(summary.getAssetItemCount()),
            String.valueOf(summary.getCountableItemCount()),
            String.valueOf(summary.getPendingItemCount()),
            String.valueOf(summary.getSyncedItemCount()),
            String.valueOf(summary.getRejectedItemCount()),
            String.valueOf(summary.getReturnPendingItemCount()),
            String.valueOf(summary.getReturnedItemCount()),
            String.valueOf(summary.getLostItemCount()),
            orEmpty(full.getFailureCause()),
            orEmpty(full.getFailureDetails()),
            orEmpty(full.getCuit()),
            orEmpty(full.getResponsibleName()),
            orEmpty(full.getResponsibleDni()),
            orEmpty(full.getAreaEvento()),
            orEmpty(full.getObservations()));
    }

    private String approvalStatusDisplay(String approvalStatus) {
        if ("PENDING".equals(approvalStatus)) return "Pendiente";
        if ("APPROVED".equals(approvalStatus)) return "Aprobada";
        if ("REJECTED".equals(approvalStatus)) return "Rechazada";
        return orEmpty(approvalStatus);
    }

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
            pw.println(String.join(",", EXPORT_HEADERS));
            for (NoteReport r : data) {
                List<String> values = exportRowValues(r);
                pw.println(values.stream().map(this::csvEscape).collect(java.util.stream.Collectors.joining(",")));
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

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(EXPORT_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            for (int i = 0; i < data.size(); i++) {
                List<String> values = exportRowValues(data.get(i));
                Row row = sheet.createRow(i + 1);
                for (int j = 0; j < values.size(); j++) {
                    row.createCell(j).setCellValue(values.get(j));
                }
            }

            for (int i = 0; i < EXPORT_HEADERS.length; i++) sheet.autoSizeColumn(i);
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
            case "ENTREGA PERMANENTE"  -> "Entrega Permanente";
            case "FIN DE CONTRATO"     -> "Entrega Permanente";
            case "ENTREGA - PROVEEDOR" -> "Entrega - Proveedor";
            case "REMITO DE ENVÍO"     -> "Remito de Envío";
            default                    -> profileType;
        };
    }
}
