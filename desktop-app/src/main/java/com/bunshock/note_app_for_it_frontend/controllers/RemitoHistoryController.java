package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
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

// "Historial de Envíos" — a Remito-scoped sibling of HistoryController/PrestamoHistoryController,
// same duplicated shape per this codebase's no-shared-abstraction convention. Unlike Préstamo,
// Remito has no per-item tracking dimension at all (no GLPI, no return status) — the only state
// that matters is the note-level approval status, since that's what actually moves stock. Row
// coloring and the dedicated status column key off approval status instead of a return-status mix.
public class RemitoHistoryController {

    @FXML private DatePicker dpFrom;
    @FXML private DatePicker dpTo;
    @FXML private MenuButton mnuApprovalStatus;
    @FXML private MenuButton mnuSede;
    @FXML private TextField  txtDestinationSearch;
    @FXML private TextField  txtAuthorSearch;
    @FXML private Label      lblPendingApproval;

    @FXML private TableView<NoteReport>           tblEnvios;
    @FXML private TableColumn<NoteReport, String> colEApproval;
    @FXML private TableColumn<NoteReport, String> colEDate;
    @FXML private TableColumn<NoteReport, String> colEAuthor;
    @FXML private TableColumn<NoteReport, String> colESede;
    @FXML private TableColumn<NoteReport, String> colEDestination;
    @FXML private TableColumn<NoteReport, String> colEAddress;
    @FXML private TableColumn<NoteReport, String> colERecipients;
    @FXML private TableColumn<NoteReport, String> colEItems;
    @FXML private TableColumn<NoteReport, String> colEStatus;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final List<String> REMITO_PROFILE_TYPES = List.of("REMITO DE ENVÍO");
    private static final List<String> APPROVAL_STATUS_OPTIONS = List.of("Pendiente", "Aprobada", "Rechazada");

    private final Set<String> selApprovalStatuses = new LinkedHashSet<>();
    private final Set<String> selSedes = new LinkedHashSet<>();
    private boolean suppressCallbacks = false;

    // Neither field is ever persisted — both are query-only, feeding HistoryFilter's
    // authorSearch/recipientSearch LIKE clauses (see buildFilter()) — so there's no DB column
    // bound to match. Capped purely as a sanity guard against an accidental huge paste.
    private static final int SEARCH_MAX_LENGTH = 255;

    public void initialize() {
        setupTable();
        resetApprovalStatusFilterToDefault();
        populateMenu(mnuApprovalStatus, APPROVAL_STATUS_OPTIONS, selApprovalStatuses, this::autoSearch);
        resetSedeFilterToDefault();
        initSedeMenu();
        txtAuthorSearch.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SEARCH_MAX_LENGTH ? change : null));
        txtDestinationSearch.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SEARCH_MAX_LENGTH ? change : null));
        loadEnvios(buildFilter());
    }

    // APROBACIÓN defaults to Pendiente+Aprobada selected, not "Todas" — a RECHAZADO note is void
    // and stays hidden from the default view, same reasoning/shape as HistoryController's and
    // PrestamoHistoryController's own versions of this method (this codebase's
    // no-shared-abstraction convention). Unlike before this change, Envíos no longer starts at
    // "Todas" (showing rechazadas by default) — unified across all three history screens.
    private void resetApprovalStatusFilterToDefault() {
        selApprovalStatuses.clear();
        selApprovalStatuses.add("Pendiente");
        selApprovalStatuses.add("Aprobada");
    }

    // Sede options come from the live SEDE catalog, not "distinct values actually seen" — same
    // reasoning as HistoryController/PrestamoHistoryController's own initSedeMenu().
    private void initSedeMenu() {
        var svc = ServiceLocator.getInstance().getEquipmentService();
        List<String> sedeNames = svc.getAllSedes().stream()
            .map(com.bunshock.note_app_for_it_frontend.models.Sede::getName)
            .toList();
        populateMenu(mnuSede, sedeNames, selSedes, this::autoSearch);
    }

    private void resetSedeFilterToDefault() {
        selSedes.clear();
        String mySede = TechnicianSessionService.getInstance().getSede();
        if (mySede != null && !mySede.isBlank()) selSedes.add(mySede);
    }

    /** Reloads with whatever filters are currently set — called by MainController every time the
     * "Historial de Envíos" flyout item is shown, same staleness fix as History/Préstamos. */
    public void refresh() {
        loadEnvios(buildFilter());
    }

    @FXML
    private void handleSearch() {
        loadEnvios(buildFilter());
    }

    @FXML
    private void handleClearFilters() {
        suppressCallbacks = true;
        dpFrom.setValue(null);
        dpTo.setValue(null);
        txtDestinationSearch.clear();
        txtAuthorSearch.clear();
        resetApprovalStatusFilterToDefault();
        resetSedeFilterToDefault();
        suppressCallbacks = false;
        populateMenu(mnuApprovalStatus, APPROVAL_STATUS_OPTIONS, selApprovalStatuses, this::autoSearch);
        initSedeMenu();
        // Goes through buildFilter() (not a bare new HistoryFilter()) so the Pendiente+Aprobada
        // default still applies after clearing — "Limpiar filtros" restores the default view, it
        // doesn't newly reveal RECHAZADO notes.
        loadEnvios(buildFilter());
    }

    private void autoSearch() {
        loadEnvios(buildFilter());
    }

    private HistoryFilter buildFilter() {
        HistoryFilter f = new HistoryFilter();
        f.setFromDate(dpFrom.getValue());
        f.setToDate(dpTo.getValue());
        f.setProfileTypes(REMITO_PROFILE_TYPES);
        String destination = txtDestinationSearch.getText();
        // recipientSearch matches COALESCE(user_name, provider_name, destination_label) —
        // destination_label is the only one of the three ever populated for a Remito.
        if (destination != null && !destination.isBlank()) f.setRecipientSearch(destination);
        String author = txtAuthorSearch.getText();
        if (author != null && !author.isBlank()) f.setAuthorSearch(author);
        if (!selApprovalStatuses.isEmpty()) {
            List<String> raw = selApprovalStatuses.stream().map(this::approvalStatusToRaw).toList();
            f.setApprovalStatuses(raw);
        }
        if (!selSedes.isEmpty()) f.setSedes(new ArrayList<>(selSedes));
        return f;
    }

    private String approvalStatusToRaw(String label) {
        return switch (label) {
            case "Aprobada" -> "APPROVED";
            case "Rechazada" -> "REJECTED";
            default -> "PENDING";
        };
    }

    // ── Filter menu (duplicated from HistoryController/PrestamoHistoryController) ─────────────

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

    // ── Table setup ───────────────────────────────────────────────────────────

    private void setupTable() {
        colEDate.setSortable(false);
        colEAuthor.setSortable(false);
        colESede.setSortable(false);
        colEDestination.setSortable(false);
        colEAddress.setSortable(false);
        colERecipients.setSortable(false);
        colEItems.setSortable(false);
        colEStatus.setSortable(false);

        colEApproval.setCellValueFactory(d -> new SimpleStringProperty(""));
        colEApproval.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                NoteReport r = empty || getTableRow() == null ? null : getTableRow().getItem();
                setStyle(r == null ? "" : "-fx-background-color: " + approvalStatusColor(r) + "; -fx-padding: 0;");
            }
        });

        colEDate.setCellValueFactory(d ->
            new SimpleStringProperty(d.getValue().getCreatedAt().format(FMT)));
        colEAuthor.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getAuthorName())));
        colESede.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getSede())));
        colEDestination.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getDestinationLabel())));
        colEAddress.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getAddress())));
        colERecipients.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getRecipients())));
        colEItems.setCellValueFactory(d -> {
            NoteReport r = d.getValue();
            int assets = r.getAssetItemCount();
            int countables = r.getCountableItemCount();
            String label = assets > 0
                ? assets + "A" + (countables > 0 ? " / " + countables + "C" : "")
                : (countables > 0 ? countables + "C" : "—");
            return new SimpleStringProperty(label);
        });
        colEStatus.setCellValueFactory(d -> new SimpleStringProperty(approvalStatusLabel(d.getValue())));

        tblEnvios.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(NoteReport report, boolean empty) {
                super.updateItem(report, empty);
                setStyle(empty || report == null ? "" : "");
            }
        });

        tblEnvios.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                NoteReport selected = tblEnvios.getSelectionModel().getSelectedItem();
                if (selected != null) openDetail(selected.getId());
            }
        });
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadEnvios(HistoryFilter filter) {
        List<NoteReport> reports = ServiceLocator.getInstance().getHistoryService().getFiltered(filter);
        tblEnvios.setItems(FXCollections.observableArrayList(reports));
        updatePendingApprovalLabel(reports);
    }

    // Envío has no per-item pending dimension of its own (no GLPI, no return status — see the
    // class comment above), so approval is the only "needs attention" count this screen shows,
    // unlike History/PrestamoHistoryController's two-pill pairs. Hidden entirely at 0, same
    // "a present badge always means something needs attention" convention as every other
    // pending-count indicator in this app.
    private void updatePendingApprovalLabel(List<NoteReport> reports) {
        long pending = reports.stream()
            .filter(r -> r.getApprovalStatus() == null || "PENDING".equals(r.getApprovalStatus()))
            .count();
        boolean show = pending > 0;
        lblPendingApproval.setText("⏳ " + pending + " pendientes de aprobación");
        lblPendingApproval.setVisible(show);
        lblPendingApproval.setManaged(show);
    }

    // Same PENDING/RECHAZADO/APPROVED → orange/red/green mapping as HistoryController/
    // PrestamoHistoryController's own approvalStatusColor()/approvalStatusDisplay().
    private String approvalStatusColor(NoteReport r) {
        String status = r.getApprovalStatus();
        if (status == null || "PENDING".equals(status)) return "#f97316";
        if ("REJECTED".equals(status)) return "#ef4444";
        return "#22c55e";
    }

    private String approvalStatusLabel(NoteReport r) {
        String status = r.getApprovalStatus();
        if (status == null || "PENDING".equals(status)) return "Pendiente";
        if ("REJECTED".equals(status)) return "Rechazada";
        if ("APPROVED".equals(status)) return "Aprobada";
        return status;
    }

    // ── Detail popup ──────────────────────────────────────────────────────────

    // Reuses the existing NoteDetailController as-is — a Remito's only admin action is the
    // note-level Aprobar/Rechazar (already generic across every profile type), and its items
    // render with no GLPI/return-status rows at all (both N_A), so no dedicated detail popup is
    // needed the way Préstamo's return-validation buttons required PrestamoDetailController.
    private void openDetail(int reportId) {
        NoteReport full = ServiceLocator.getInstance().getHistoryService().getById(reportId);
        if (full == null) return;
        try {
            NoteDetailController.open(full, AdminSession.getInstance().isActive(),
                tblEnvios.getScene().getWindow(),
                () -> loadEnvios(buildFilter()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
