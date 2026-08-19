package com.bunshock.note_app_for_it_frontend.controllers.prestamo;
import com.bunshock.note_app_for_it_frontend.controllers.history.PrestamoDetailController;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.models.history.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
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

// "Historial de Préstamos" — a Préstamo-scoped sibling of HistoryController, without any GLPI
// sync affordance (Préstamos track return status instead — see PrestamoDetailController). Row
// coloring/menu-filter/detail-popup wiring intentionally duplicates HistoryController's shape,
// keyed on the new return-status counts instead of GLPI ones — see CLAUDE.md's "Préstamos section".
public class PrestamoHistoryController {

    @FXML private DatePicker dpFrom;
    @FXML private DatePicker dpTo;
    @FXML private MenuButton mnuReturnStatus;
    @FXML private MenuButton mnuApprovalStatus;
    @FXML private MenuButton mnuSede;
    @FXML private TextField  txtRecipientSearch;
    @FXML private TextField  txtAuthorSearch;
    @FXML private Label      lblPendingApproval;
    @FXML private Label      lblPendingReturns;

    @FXML private TableView<NoteReport>           tblPrestamos;
    @FXML private TableColumn<NoteReport, String> colPApproval;
    @FXML private TableColumn<NoteReport, String> colPDate;
    @FXML private TableColumn<NoteReport, String> colPAuthor;
    @FXML private TableColumn<NoteReport, String> colPSede;
    @FXML private TableColumn<NoteReport, String> colPRecipient;
    @FXML private TableColumn<NoteReport, String> colPFechaTentativa;
    @FXML private TableColumn<NoteReport, String> colPItems;
    @FXML private TableColumn<NoteReport, String> colPStatus;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter MOTIVO_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Same case/accent variants SqliteHistoryService.isPrestamo() tolerates.
    private static final List<String> PRESTAMO_PROFILE_TYPES = List.of("PRÉSTAMO", "PRESTAMO", "Préstamo");

    private static final List<String> RETURN_STATUS_OPTIONS =
        List.of("Pendiente", "Devuelto", "Perdido", "Mixto", "Vencido");

    private static final List<String> APPROVAL_STATUS_OPTIONS = List.of("Pendiente", "Aprobada", "Rechazada");

    private final Set<String> selReturnStatuses   = new LinkedHashSet<>();
    private final Set<String> selApprovalStatuses = new LinkedHashSet<>();
    private final Set<String> selSedes = new LinkedHashSet<>();
    private boolean suppressCallbacks = false;

    // Neither field is ever persisted — both are query-only, feeding HistoryFilter's
    // authorSearch/recipientSearch LIKE clauses (see buildFilter()) — so there's no DB column
    // bound to match. Capped purely as a sanity guard against an accidental huge paste.
    private static final int SEARCH_MAX_LENGTH = 255;

    public void initialize() {
        setupTable();
        populateMenu(mnuReturnStatus, RETURN_STATUS_OPTIONS, selReturnStatuses, this::autoSearch);
        resetApprovalStatusFilterToDefault();
        populateMenu(mnuApprovalStatus, APPROVAL_STATUS_OPTIONS, selApprovalStatuses, this::autoSearch);
        resetSedeFilterToDefault();
        initSedeMenu();
        txtAuthorSearch.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SEARCH_MAX_LENGTH ? change : null));
        txtRecipientSearch.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SEARCH_MAX_LENGTH ? change : null));
        loadPrestamos(buildFilter());
    }

    // APROBACIÓN defaults to Pendiente+Aprobada selected, not "Todas" — a RECHAZADO note is void
    // and stays hidden from the default view, same reasoning as HistoryController's own version
    // of this method (this codebase's no-shared-abstraction convention).
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

    // Sede options come from the live SEDE catalog (same source Configuración/Base de Datos use),
    // not "distinct values actually seen in history" — a technician's own Sede (the default
    // filter, see resetSedeFilterToDefault()) may not have any Préstamo history yet at all.
    private void initSedeMenu() {
        var svc = ServiceLocator.getInstance().getEquipmentService();
        List<String> sedeNames = svc.getAllSedes().stream()
            .map(com.bunshock.note_app_for_it_frontend.models.catalog.Sede::getName)
            .toList();
        populateMenu(mnuSede, sedeNames, selSedes, this::autoSearch);
    }

    // The Sede filter defaults to the technician's own assigned Sede (if any) rather than "Todas"
    // — same default-view precedent as HistoryController's own resetSedeFilterToDefault(). Still
    // just a starting point: the technician can clear or change it like any other filter.
    private void resetSedeFilterToDefault() {
        selSedes.clear();
        String mySede = TechnicianSessionService.getInstance().getSede();
        if (mySede != null && !mySede.isBlank()) selSedes.add(mySede);
    }

    /** Reloads with whatever filters are currently set — called by PrestamosController every time
     * the Préstamos section's Historial tab is shown, same staleness fix as HistoryController.refresh(). */
    public void refresh() {
        loadPrestamos(buildFilter());
    }

    @FXML
    private void handleSearch() {
        loadPrestamos(buildFilter());
    }

    @FXML
    private void handleClearFilters() {
        suppressCallbacks = true;
        dpFrom.setValue(null);
        dpTo.setValue(null);
        txtRecipientSearch.clear();
        txtAuthorSearch.clear();
        selReturnStatuses.clear();
        resetSedeFilterToDefault();
        resetApprovalStatusFilterToDefault();
        suppressCallbacks = false;
        populateMenu(mnuReturnStatus, RETURN_STATUS_OPTIONS, selReturnStatuses, this::autoSearch);
        populateMenu(mnuApprovalStatus, APPROVAL_STATUS_OPTIONS, selApprovalStatuses, this::autoSearch);
        initSedeMenu();
        // Goes through buildFilter() (not a bare new HistoryFilter()) so the PENDING+APPROVED
        // default still applies after clearing — same "Limpiar filtros restores the default view,
        // it doesn't newly reveal RECHAZADO notes" precedent as HistoryController's own version.
        loadPrestamos(buildFilter());
    }

    private void autoSearch() {
        loadPrestamos(buildFilter());
    }

    private HistoryFilter buildFilter() {
        HistoryFilter f = new HistoryFilter();
        f.setFromDate(dpFrom.getValue());
        f.setToDate(dpTo.getValue());
        f.setProfileTypes(PRESTAMO_PROFILE_TYPES);
        String recipient = txtRecipientSearch.getText();
        if (recipient != null && !recipient.isBlank()) f.setRecipientSearch(recipient);
        String author = txtAuthorSearch.getText();
        if (author != null && !author.isBlank()) f.setAuthorSearch(author);
        if (!selSedes.isEmpty()) f.setSedes(new ArrayList<>(selSedes));
        // Default selection is Pendiente+Aprobada (see resetApprovalStatusFilterToDefault()) —
        // RECHAZADO notes are void and stay hidden from the default view. Unchecking down to
        // "Todas" (empty set) applies no filter at all; explicitly picking Rechazada shows it.
        if (!selApprovalStatuses.isEmpty()) {
            f.setApprovalStatuses(selApprovalStatuses.stream().map(this::approvalStatusToRaw).toList());
        }
        return f;
    }

    // ── Filter menu (duplicated from HistoryController.populateMenu/updateMenuLabel) ──────────

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
                // "Todas" can only ever go true->false when nothing else is selected (an
                // individual checkbox being checked already un-checks it directly, above, under
                // lock — see that listener). Manually unchecking it here would leave nothing
                // visibly selected while the filter still silently matches everything — direct
                // user report. Snap it back instead of allowing that dead state.
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
        colPDate.setSortable(false);
        colPAuthor.setSortable(false);
        colPSede.setSortable(false);
        colPRecipient.setSortable(false);
        colPFechaTentativa.setSortable(false);
        colPItems.setSortable(false);
        colPStatus.setSortable(false);

        // A dedicated narrow column, not a row border — see HistoryController's identical
        // colGApproval setup (duplicated per this codebase's no-shared-abstraction convention)
        // for why: a border always consumes layout space, shifting cell content relative to the
        // column headers, which a real TableColumn's own header never has to worry about.
        colPApproval.setCellValueFactory(d -> new SimpleStringProperty(""));
        colPApproval.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                NoteReport r = empty || getTableRow() == null ? null : getTableRow().getItem();
                setStyle(r == null ? "" : "-fx-background-color: " + approvalStatusColor(r) + "; -fx-padding: 0;");
            }
        });

        colPDate.setCellValueFactory(d ->
            new SimpleStringProperty(d.getValue().getCreatedAt().format(FMT)));
        colPAuthor.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getAuthorName())));
        colPSede.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getSede())));
        colPRecipient.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getRecipientDisplay())));
        // NOTE_REPORT.motivo is overloaded for Préstamo notes to store the tentative return date
        // (see CLAUDE.md's "Note types and profiles" table) rather than a real Motivo — every row
        // in this table is a Préstamo, so the column is labeled for what it actually holds here.
        colPFechaTentativa.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getMotivo())));
        colPItems.setCellValueFactory(d -> {
            NoteReport r = d.getValue();
            int assets = r.getAssetItemCount();
            int countables = r.getCountableItemCount();
            String label = assets > 0
                ? assets + "A" + (countables > 0 ? " / " + countables + "C" : "")
                : (countables > 0 ? countables + "C" : "—");
            return new SimpleStringProperty(label);
        });
        colPStatus.setCellValueFactory(d -> new SimpleStringProperty(returnStatusLabel(d.getValue())));

        tblPrestamos.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(NoteReport report, boolean empty) {
                super.updateItem(report, empty);
                setStyle(empty || report == null ? "" : computeRowStyle(report));
            }
        });

        tblPrestamos.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                NoteReport selected = tblPrestamos.getSelectionModel().getSelectedItem();
                if (selected != null) openDetail(selected.getId());
            }
        });
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadPrestamos(HistoryFilter filter) {
        List<NoteReport> reports = ServiceLocator.getInstance().getHistoryService().getFiltered(filter);
        if (!selReturnStatuses.isEmpty()) {
            reports = reports.stream()
                .filter(r -> selReturnStatuses.stream().anyMatch(s -> matchesReturnStatusLabel(r, s)))
                .toList();
        }
        tblPrestamos.setItems(FXCollections.observableArrayList(reports));
        updatePendingApprovalLabel(reports);
        updatePendingReturnsLabel(reports);
    }

    // Counts notes among the currently-filtered rows still awaiting approval — a separate
    // concern from return-pending (below), red instead of orange so the two aren't mistaken for
    // the same count, same distinction MainController's own nav badges already make between
    // .nav-badge/.nav-badge-approval. Hidden entirely at 0, same "a present badge always means
    // something needs attention" convention as every other pending-count indicator in this app.
    private void updatePendingApprovalLabel(List<NoteReport> reports) {
        long pending = reports.stream()
            .filter(r -> r.getApprovalStatus() == null || "PENDING".equals(r.getApprovalStatus()))
            .count();
        boolean show = pending > 0;
        lblPendingApproval.setText("⏳ " + pending + " pendientes de aprobación");
        lblPendingApproval.setVisible(show);
        lblPendingApproval.setManaged(show);
    }

    // Counts notes among the currently-filtered rows that still have at least one item pending
    // return AND are already APPROVED — a note still awaiting approval isn't a real loan yet (an
    // admin might reject it outright), and a rejected note's items should never count as pending
    // either; only an approved note's pending return is genuinely "needs attention." Scoped to
    // whatever Fecha/Sede/Estado/search filters are active, not a global total (that's what the
    // sidebar's lblPrestamosBadge already shows). Hidden entirely at 0, same "a present badge
    // always means something needs attention" convention as every other pending-count indicator
    // in this app (see MainController.updateBadge()).
    private void updatePendingReturnsLabel(List<NoteReport> reports) {
        long pending = reports.stream()
            .filter(r -> "APPROVED".equals(r.getApprovalStatus()))
            .filter(r -> r.getReturnPendingItemCount() > 0)
            .count();
        boolean show = pending > 0;
        lblPendingReturns.setText("⏳ " + pending + " con devolución pendiente");
        lblPendingReturns.setVisible(show);
        lblPendingReturns.setManaged(show);
    }

    // ── Row color + overdue ───────────────────────────────────────────────────

    private String computeRowStyle(NoteReport r) {
        int total = r.getReturnPendingItemCount() + r.getReturnedItemCount() + r.getLostItemCount();
        String background;
        if (total == 0) {
            background = "-fx-background-color: #f1f5f9;";
        } else {
            int pending  = r.getReturnPendingItemCount();
            int returned = r.getReturnedItemCount();
            int lost     = r.getLostItemCount();

            if (pending  == total) background = "-fx-background-color: rgba(251,146,60,0.18);";
            else if (returned == total) background = "-fx-background-color: rgba(34,197,94,0.18);";
            else if (lost == total) background = "-fx-background-color: rgba(239,68,68,0.18);";
            else {
                StringBuilder g = new StringBuilder("-fx-background-color: linear-gradient(to right");
                double pos = 0;
                pos = appendSlice(g, lost, total, pos, "239,68,68");
                pos = appendSlice(g, pending, total, pos, "251,146,60");
                appendSlice(g, returned, total, pos, "34,197,94");
                g.append(");");
                background = g.toString();
            }
        }

        // Approval status used to also contribute a left-border layer here (composed alongside
        // this bottom border via the same multi-layer -fx-border-color/-fx-border-width trick,
        // since those are single, non-additive CSS properties) — moved to the dedicated
        // colPApproval column instead, so only the overdue bottom-border remains.
        if (isOverdue(r)) {
            background += " -fx-border-color: #ef4444; -fx-border-width: 0 0 2 0;";
        }
        return background;
    }

    // Color for the dedicated colPApproval status column — same logic as HistoryController's
    // approvalStatusColor() (duplicated per this codebase's no-shared-abstraction convention).
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

    /** Overdue = still has pending items and the tentative return date (stored in Motivo,
     * dd/MM/yyyy — see CLAUDE.md's "Note types and profiles") has already passed. */
    private boolean isOverdue(NoteReport r) {
        if (r.getReturnPendingItemCount() <= 0) return false;
        LocalDate tentative = parseMotivoDate(r.getMotivo());
        return tentative != null && tentative.isBefore(LocalDate.now());
    }

    private LocalDate parseMotivoDate(String motivo) {
        if (motivo == null || motivo.isBlank()) return null;
        try {
            return LocalDate.parse(motivo, MOTIVO_DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String returnStatusLabel(NoteReport r) {
        int total = r.getReturnPendingItemCount() + r.getReturnedItemCount() + r.getLostItemCount();
        if (total == 0) return "—";
        int pending  = r.getReturnPendingItemCount();
        int returned = r.getReturnedItemCount();
        int lost     = r.getLostItemCount();
        String base;
        if (pending == total) base = "Pendiente";
        else if (returned == total) base = "Devuelto";
        else if (lost == total) base = "Perdido";
        else base = "Mixto";
        return isOverdue(r) ? base + " (Vencido)" : base;
    }

    private boolean matchesReturnStatusLabel(NoteReport r, String label) {
        int total = r.getReturnPendingItemCount() + r.getReturnedItemCount() + r.getLostItemCount();
        int pending  = r.getReturnPendingItemCount();
        int returned = r.getReturnedItemCount();
        int lost     = r.getLostItemCount();
        return switch (label) {
            case "Pendiente" -> pending > 0;
            case "Devuelto"  -> returned > 0;
            case "Perdido"   -> lost > 0;
            case "Mixto"     -> total > 0 && pending != total && returned != total && lost != total;
            case "Vencido"   -> isOverdue(r);
            default          -> true;
        };
    }

    // ── Detail popup ──────────────────────────────────────────────────────────

    private void openDetail(int reportId) {
        NoteReport full = ServiceLocator.getInstance().getHistoryService().getById(reportId);
        if (full == null) return;
        try {
            PrestamoDetailController.open(full, AdminSession.getInstance().isActive(),
                tblPrestamos.getScene().getWindow(),
                () -> loadPrestamos(buildFilter()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
