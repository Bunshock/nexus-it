package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.controllers.HistoryController;
import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;

import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.DatePicker;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryControllerTest {

    @BeforeAll
    static void initFxToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
    }

    private final HistoryController controller = new HistoryController();

    private String csvEscape(String s) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("csvEscape", String.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, s);
    }

    private String glpiStatusLabel(NoteReport r) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("glpiStatusLabel", NoteReport.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, r);
    }

    private String computeRowStyle(NoteReport r) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("computeRowStyle", NoteReport.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, r);
    }

    private String approvalStatusColor(NoteReport r) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("approvalStatusColor", NoteReport.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, r);
    }

    private String approvalStatusDisplay(String approvalStatus) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("approvalStatusDisplay", String.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, approvalStatus);
    }

    private String toDisplayName(String profileType) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("toDisplayName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, profileType);
    }

    @SuppressWarnings("unchecked")
    private List<String> expandProfileTypeLabels(Set<String> labels) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("expandProfileTypeLabels", Set.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, labels);
    }

    private NoteReport reportWith(int assets, int pending, int synced, int rejected) {
        NoteReport r = new NoteReport();
        r.setAssetItemCount(assets);
        r.setPendingItemCount(pending);
        r.setSyncedItemCount(synced);
        r.setRejectedItemCount(rejected);
        // APPROVED so these GLPI-focused tests aren't coupled to approval status — NoteReport
        // defaults to PENDING otherwise. Approval status no longer affects computeRowStyle()'s
        // return value at all (moved to its own dedicated column, see approvalStatusColor() below)
        // but keeping this explicit avoids these tests silently depending on that default.
        r.setApprovalStatus("APPROVED");
        return r;
    }

    // ── csvEscape ─────────────────────────────────────────────────────────────

    @Test
    void csvEscapeReturnsEmptyStringForNull() throws Exception {
        assertEquals("", csvEscape(null));
    }

    @Test
    void csvEscapeLeavesPlainTextUnchanged() throws Exception {
        assertEquals("Juan Perez", csvEscape("Juan Perez"));
    }

    @Test
    void csvEscapeQuotesValueContainingComma() throws Exception {
        assertEquals("\"Perez, Juan\"", csvEscape("Perez, Juan"));
    }

    @Test
    void csvEscapeDoublesInternalQuotes() throws Exception {
        assertEquals("\"Dijo \"\"hola\"\"\"", csvEscape("Dijo \"hola\""));
    }

    @Test
    void csvEscapeQuotesValueContainingNewline() throws Exception {
        assertEquals("\"line1\nline2\"", csvEscape("line1\nline2"));
    }

    // ── glpiStatusLabel ───────────────────────────────────────────────────────

    @Test
    void glpiStatusLabelNoAssetsReturnsDash() throws Exception {
        assertEquals("—", glpiStatusLabel(reportWith(0, 0, 0, 0)));
    }

    @Test
    void glpiStatusLabelAllPendingReturnsPendiente() throws Exception {
        assertEquals("Pendiente", glpiStatusLabel(reportWith(2, 2, 0, 0)));
    }

    @Test
    void glpiStatusLabelAllSyncedReturnsSincronizado() throws Exception {
        assertEquals("Sincronizado", glpiStatusLabel(reportWith(2, 0, 2, 0)));
    }

    @Test
    void glpiStatusLabelAllRejectedReturnsRechazado() throws Exception {
        assertEquals("Rechazado", glpiStatusLabel(reportWith(2, 0, 0, 2)));
    }

    @Test
    void glpiStatusLabelMixedReturnsMixto() throws Exception {
        assertEquals("Mixto", glpiStatusLabel(reportWith(2, 1, 1, 0)));
    }

    // Reproduces a real reported bug: a Préstamo note's assets are all glpi_status N_A (see
    // CLAUDE.md's "Préstamo assets are deliberately excluded from GLPI sync"), so
    // getAssetItemCount() is > 0 even though none of pending/synced/rejected are — the old
    // implementation used raw asset count as "total" and fell through to "Mixto" for this case
    // instead of recognizing there's nothing GLPI-tracked on the note at all.
    @Test
    void glpiStatusLabelAllNaAssetsReturnsDash() throws Exception {
        assertEquals("—", glpiStatusLabel(reportWith(1, 0, 0, 0)));
    }

    // ── computeRowStyle ───────────────────────────────────────────────────────

    // These five assert only the background prefix (startsWith), not full-string equality — kept
    // as startsWith from when computeRowStyle() also appended an approval-status border suffix;
    // no longer strictly necessary now that the border was moved out to its own column (see
    // approvalStatusColor() below), but left as-is since it's still a correct, harmless assertion.

    @Test
    void computeRowStyleNoAssetsReturnsNeutralColor() throws Exception {
        assertTrue(computeRowStyle(reportWith(0, 0, 0, 0)).startsWith("-fx-background-color: #f1f5f9;"));
    }

    @Test
    void computeRowStyleAllPendingReturnsSolidOrange() throws Exception {
        assertTrue(computeRowStyle(reportWith(3, 3, 0, 0)).startsWith("-fx-background-color: rgba(251,146,60,0.18);"));
    }

    @Test
    void computeRowStyleAllSyncedReturnsSolidGreen() throws Exception {
        assertTrue(computeRowStyle(reportWith(3, 0, 3, 0)).startsWith("-fx-background-color: rgba(34,197,94,0.18);"));
    }

    @Test
    void computeRowStyleAllRejectedReturnsSolidRed() throws Exception {
        assertTrue(computeRowStyle(reportWith(3, 0, 0, 3)).startsWith("-fx-background-color: rgba(239,68,68,0.18);"));
    }

    // Same bug as glpiStatusLabelAllNaAssetsReturnsDash — the old code fell through to a
    // zero-color-stop "linear-gradient(to right);" for this case instead of the flat neutral
    // color used for "nothing GLPI-tracked" everywhere else.
    @Test
    void computeRowStyleAllNaAssetsReturnsNeutralColor() throws Exception {
        assertTrue(computeRowStyle(reportWith(1, 0, 0, 0)).startsWith("-fx-background-color: #f1f5f9;"));
    }

    @Test
    void computeRowStyleMixedReturnsGradientSplitByShare() throws Exception {
        String style = computeRowStyle(reportWith(2, 1, 1, 0));
        assertTrue(style.startsWith("-fx-background-color: linear-gradient(to right"));
        assertTrue(style.contains("rgba(251,146,60,0.25) 0.00%"));
        assertTrue(style.contains("rgba(251,146,60,0.25) 50.00%"));
        assertTrue(style.contains("rgba(34,197,94,0.25) 50.00%"));
        assertTrue(style.contains("rgba(34,197,94,0.25) 100.00%);"));
    }

    // ── computeRowStyle for Préstamo notes (return-status-based, not GLPI-based) ──────────────
    // A Préstamo note's GLPI counts (pending/synced/rejected) are always 0 — see reportWith()'s
    // own comment on why — so without this branch every Préstamo row would fall into the plain
    // "nothing to track" gray above regardless of actual return progress. Same coloring math as
    // PrestamoHistoryControllerTest's own computeRowStyle tests, just reached through
    // HistoryController's profile-type branch instead.

    private NoteReport prestamoReportWith(int pending, int returned, int lost) {
        NoteReport r = new NoteReport();
        r.setProfileType("PRÉSTAMO");
        r.setReturnPendingItemCount(pending);
        r.setReturnedItemCount(returned);
        r.setLostItemCount(lost);
        r.setApprovalStatus("APPROVED");
        return r;
    }

    @Test
    void computeRowStyleForPrestamoNoReturnDataReturnsNeutralColor() throws Exception {
        assertTrue(computeRowStyle(prestamoReportWith(0, 0, 0)).startsWith("-fx-background-color: #f1f5f9;"));
    }

    @Test
    void computeRowStyleForPrestamoAllPendingReturnsSolidOrange() throws Exception {
        assertTrue(computeRowStyle(prestamoReportWith(3, 0, 0)).startsWith("-fx-background-color: rgba(251,146,60,0.18);"));
    }

    @Test
    void computeRowStyleForPrestamoAllReturnedReturnsSolidGreen() throws Exception {
        assertTrue(computeRowStyle(prestamoReportWith(0, 3, 0)).startsWith("-fx-background-color: rgba(34,197,94,0.18);"));
    }

    @Test
    void computeRowStyleForPrestamoAllLostReturnsSolidRed() throws Exception {
        assertTrue(computeRowStyle(prestamoReportWith(0, 0, 3)).startsWith("-fx-background-color: rgba(239,68,68,0.18);"));
    }

    @Test
    void computeRowStyleForPrestamoMixedReturnsGradient() throws Exception {
        assertTrue(computeRowStyle(prestamoReportWith(1, 1, 1)).startsWith("-fx-background-color: linear-gradient(to right"));
    }

    // ── toDisplayName ─────────────────────────────────────────────────────────

    @Test
    void toDisplayNameMapsRawProfileTypesToNiceNames() throws Exception {
        assertEquals("Entrega", toDisplayName("ENTREGA"));
        assertEquals("Devolución", toDisplayName("DEVOLUCIÓN"));
        assertEquals("Préstamo", toDisplayName("PRÉSTAMO"));
        assertEquals("Entrega Permanente", toDisplayName("ENTREGA PERMANENTE"));
        assertEquals("Entrega - Proveedor", toDisplayName("ENTREGA - PROVEEDOR"));
        assertEquals("Remito de Envío", toDisplayName("REMITO DE ENVÍO"));
    }

    @Test
    void toDisplayNameReturnsEmptyForNull() throws Exception {
        assertEquals("", toDisplayName(null));
    }

    @Test
    void toDisplayNamePassesThroughUnknownValues() throws Exception {
        assertEquals("Algo Nuevo", toDisplayName("Algo Nuevo"));
    }

    // ── expandProfileTypeLabels ──────────────────────────────────────────────

    @Test
    void expandProfileTypeLabelsMatchesBothRawCasingVariants() throws Exception {
        // "Devolución" (seed-data casing) and "DEVOLUCIÓN" (live-UI raw casing) must both
        // be searched for a single "Devolución" filter checkbox, or one or the other silently
        // disappears from filtered results depending on how that specific row was created.
        List<String> expanded = expandProfileTypeLabels(new LinkedHashSet<>(Set.of("Devolución")));
        assertTrue(expanded.contains("DEVOLUCIÓN"));
        assertTrue(expanded.contains("Devolución"));
    }

    @Test
    void expandProfileTypeLabelsMatchesRemitoDeEnvio() throws Exception {
        List<String> expanded = expandProfileTypeLabels(new LinkedHashSet<>(Set.of("Remito de Envío")));
        assertTrue(expanded.contains("REMITO DE ENVÍO"));
        assertTrue(expanded.contains("Remito de Envío"));
    }

    @Test
    void expandProfileTypeLabelsHandlesEntregaPermanenteAliases() throws Exception {
        // "Entrega Permanente" is the current display label; the raw variants it must still
        // match include the old display casing ("Fin de Contrato") for rows created before the
        // rename, plus the raw stored value(s) — the rename never touched what's in the DB.
        List<String> expanded = expandProfileTypeLabels(new LinkedHashSet<>(Set.of("Entrega Permanente")));
        assertTrue(expanded.contains("ENTREGA PERMANENTE"));
        assertTrue(expanded.contains("Fin de Contrato"));
    }

    @Test
    void expandProfileTypeLabelsPassesThroughUnknownLabelUnchanged() throws Exception {
        List<String> expanded = expandProfileTypeLabels(new LinkedHashSet<>(Set.of("Recambio")));
        assertEquals(List.of("Recambio"), expanded);
    }

    @Test
    void expandProfileTypeLabelsHandlesMultipleSelections() throws Exception {
        List<String> expanded = expandProfileTypeLabels(new LinkedHashSet<>(Set.of("Entrega", "Préstamo")));
        assertTrue(expanded.containsAll(List.of("ENTREGA", "Entrega", "PRÉSTAMO", "Préstamo")));
        assertEquals(4, expanded.size());
    }

    // ── populateMenu "Todas" checkbox ────────────────────────────────────

    private Method populateMenuMethod() throws Exception {
        Method m = HistoryController.class.getDeclaredMethod(
            "populateMenu", MenuButton.class, List.class, Set.class, Runnable.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void populateMenuPreventsUncheckingTodasWhenNothingElseSelected() throws Exception {
        MenuButton btn = new MenuButton();
        Set<String> selected = new LinkedHashSet<>();
        populateMenuMethod().invoke(controller, btn, List.of("A", "B"), selected, (Runnable) () -> {});

        CheckBox todasChk = (CheckBox) ((CustomMenuItem) btn.getItems().get(0)).getContent();
        assertTrue(todasChk.isSelected(), "Todas starts checked when nothing is selected");

        todasChk.setSelected(false);

        assertTrue(todasChk.isSelected(),
            "unchecking Todas with nothing else selected must snap back — direct user report that "
                + "it could otherwise be left unchecked while the filter still silently matched everything");
        assertTrue(selected.isEmpty(), "the underlying filter set is untouched by the snap-back");
    }

    @Test
    void populateMenuStillTurnsTodasOffWhenAnItemIsSelected() throws Exception {
        MenuButton btn = new MenuButton();
        Set<String> selected = new LinkedHashSet<>();
        populateMenuMethod().invoke(controller, btn, List.of("A", "B"), selected, (Runnable) () -> {});

        CheckBox todasChk = (CheckBox) ((CustomMenuItem) btn.getItems().get(0)).getContent();
        CheckBox itemA = (CheckBox) ((CustomMenuItem) btn.getItems().get(2)).getContent();

        itemA.setSelected(true);

        assertFalse(todasChk.isSelected(), "selecting a real item still turns Todas off normally");
        assertEquals(Set.of("A"), selected);
    }

    // ── buildFilter (approval-status default) ───────────────────────────────

    private void setField(HistoryController c, String name, Object value) throws Exception {
        Field f = HistoryController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(c, value);
    }

    private HistoryFilter buildFilter(HistoryController c) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("buildFilter");
        m.setAccessible(true);
        return (HistoryFilter) m.invoke(c);
    }

    @SuppressWarnings("unchecked")
    private Set<String> selApprovalStatusesField(HistoryController c) throws Exception {
        Field f = HistoryController.class.getDeclaredField("selApprovalStatuses");
        f.setAccessible(true);
        return (Set<String>) f.get(c);
    }

    private void invokeResetApprovalStatusFilterToDefault(HistoryController c) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("resetApprovalStatusFilterToDefault");
        m.setAccessible(true);
        m.invoke(c);
    }

    // Seeds every field buildFilter() reads — a bare `new HistoryController()` never runs
    // initialize(), so these @FXML fields are otherwise null.
    private HistoryController controllerWithFilterFieldsSeeded() throws Exception {
        HistoryController c = new HistoryController();
        setField(c, "dpFrom", new DatePicker());
        setField(c, "dpTo", new DatePicker());
        setField(c, "txtRecipientSearch", new TextField());
        setField(c, "txtAuthorSearch", new TextField());
        return c;
    }

    @Test
    void resetApprovalStatusFilterToDefaultSelectsPendingAndAprobada() throws Exception {
        HistoryController c = controllerWithFilterFieldsSeeded();
        invokeResetApprovalStatusFilterToDefault(c);
        assertEquals(Set.of("Pendiente", "Aprobada"), selApprovalStatusesField(c));
    }

    @Test
    void buildFilterDefaultsToPendingAndApprovedWhenApprovalMenuAtItsDefault() throws Exception {
        HistoryController c = controllerWithFilterFieldsSeeded();
        invokeResetApprovalStatusFilterToDefault(c);
        HistoryFilter f = buildFilter(c);
        assertEquals(List.of("PENDING", "APPROVED"), f.getApprovalStatuses());
    }

    // An empty selApprovalStatuses set is "Todas" (unchecked down from the default, same as any
    // other multi-select menu in this app) — no filter at all, RECHAZADO included.
    @Test
    void buildFilterAppliesNoApprovalFilterWhenApprovalMenuIsTodas() throws Exception {
        HistoryFilter f = buildFilter(controllerWithFilterFieldsSeeded());
        assertNull(f.getApprovalStatuses());
    }

    @Test
    void buildFilterAppliesExplicitApprovalSelectionIncludingRechazada() throws Exception {
        HistoryController c = controllerWithFilterFieldsSeeded();
        selApprovalStatusesField(c).add("Rechazada");
        HistoryFilter f = buildFilter(c);
        assertEquals(List.of("REJECTED"), f.getApprovalStatuses());
    }

    // ── approvalStatusColor (dedicated status column, colGApproval) ──────────
    //
    // Used to be an always-on left border layered onto computeRowStyle()'s return value —
    // replaced because any left/right border consumes layout space, shifting every
    // row's cell content relative to the column headers (which have no matching border/inset of
    // their own). Moved to a real, narrow TableColumn instead, whose header reserves the exact
    // same width as its cells automatically — see setupTable()'s colGApproval wiring.

    @Test
    void approvalStatusColorPendingIsOrange() throws Exception {
        NoteReport r = reportWith(0, 0, 0, 0);
        r.setApprovalStatus("PENDING");
        assertEquals("#f97316", approvalStatusColor(r));
    }

    @Test
    void approvalStatusColorRechazadoIsRed() throws Exception {
        NoteReport r = reportWith(0, 0, 0, 0);
        r.setApprovalStatus("REJECTED");
        assertEquals("#ef4444", approvalStatusColor(r));
    }

    @Test
    void approvalStatusColorApprovedIsGreen() throws Exception {
        assertEquals("#22c55e", approvalStatusColor(reportWith(0, 0, 0, 0)));
    }

    @Test
    void computeRowStyleNoLongerAppendsAnyBorder() throws Exception {
        NoteReport r = reportWith(0, 0, 0, 0);
        r.setApprovalStatus("PENDING");
        assertFalse(computeRowStyle(r).contains("-fx-border"));
    }

    // ── approvalStatusDisplay (export "Estado" column) ────────────────────────

    @Test
    void approvalStatusDisplayMapsKnownValues() throws Exception {
        assertEquals("Pendiente", approvalStatusDisplay("PENDING"));
        assertEquals("Aprobada", approvalStatusDisplay("APPROVED"));
        assertEquals("Rechazada", approvalStatusDisplay("REJECTED"));
    }

    @Test
    void approvalStatusDisplayFallsBackToRawValueForUnknownStatus() throws Exception {
        assertEquals("SOMETHING_ELSE", approvalStatusDisplay("SOMETHING_ELSE"));
    }

    // ── Sede filter defaults to the technician's own assigned Sede ───────────────

    @SuppressWarnings("unchecked")
    private Set<String> selSedesField(HistoryController c) throws Exception {
        Field f = HistoryController.class.getDeclaredField("selSedes");
        f.setAccessible(true);
        return (Set<String>) f.get(c);
    }

    private void invokeResetSedeFilterToDefault(HistoryController c) throws Exception {
        Method m = HistoryController.class.getDeclaredMethod("resetSedeFilterToDefault");
        m.setAccessible(true);
        m.invoke(c);
    }

    // Sets the singleton's resolved Sede name directly via reflection, bypassing the real
    // login/APP_USER round trip — resetSedeFilterToDefault() only ever reads the public getSede()
    // getter, so this is enough to exercise it without a live database or MockUserRoleService.
    private void setTechnicianSede(String sedeName) throws Exception {
        Field f = com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService.class
            .getDeclaredField("sedeName");
        f.setAccessible(true);
        f.set(com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService.getInstance(), sedeName);
    }

    @Test
    void resetSedeFilterToDefaultAddsTechniciansOwnSedeWhenAssigned() throws Exception {
        setTechnicianSede("Campus Norte");
        try {
            HistoryController c = new HistoryController();
            invokeResetSedeFilterToDefault(c);
            assertEquals(Set.of("Campus Norte"), selSedesField(c));
        } finally {
            setTechnicianSede(null);
        }
    }

    @Test
    void resetSedeFilterToDefaultLeavesEmptyWhenNoSedeAssigned() throws Exception {
        setTechnicianSede(null);
        HistoryController c = new HistoryController();
        invokeResetSedeFilterToDefault(c);
        assertTrue(selSedesField(c).isEmpty());
    }

    @Test
    void sedeCatalogNamesReturnsActiveSedesFromEquipmentService() throws Exception {
        var mockEquipment = new com.bunshock.note_app_for_it_frontend.services.MockEquipmentService();
        mockEquipment.addSede("Campus Norte");
        mockEquipment.addSede("Campus Sur");
        com.bunshock.note_app_for_it_frontend.services.ServiceLocator.getInstance()
            .setEquipmentService(mockEquipment);

        Method m = HistoryController.class.getDeclaredMethod("sedeCatalogNames");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) m.invoke(controller);

        assertTrue(names.contains("Campus Norte"));
        assertTrue(names.contains("Campus Sur"));
    }

    // ── updatePendingApprovalLabel / updatePendingGlpiLabel (filter-row pills) ─────────────────

    private javafx.scene.control.Label invokePendingLabelUpdater(
            String methodName, String fieldName, List<NoteReport> reports) throws Exception {
        HistoryController c = new HistoryController();
        javafx.scene.control.Label lbl = new javafx.scene.control.Label();
        Field f = HistoryController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(c, lbl);

        Method m = HistoryController.class.getDeclaredMethod(methodName, List.class);
        m.setAccessible(true);
        m.invoke(c, reports);
        return lbl;
    }

    @Test
    void updatePendingApprovalLabelHiddenWhenNothingIsPending() throws Exception {
        NoteReport approved = reportWith(1, 1, 0, 0);
        NoteReport rejected = reportWith(1, 0, 0, 1);
        rejected.setApprovalStatus("REJECTED");

        javafx.scene.control.Label lbl = invokePendingLabelUpdater(
            "updatePendingApprovalLabel", "lblPendingApproval", List.of(approved, rejected));

        assertFalse(lbl.isVisible());
        assertFalse(lbl.isManaged());
    }

    @Test
    void updatePendingApprovalLabelShowsCountOfNotesAwaitingApproval() throws Exception {
        NoteReport pending1 = reportWith(1, 1, 0, 0);
        pending1.setApprovalStatus("PENDING");
        NoteReport pending2 = reportWith(0, 0, 0, 0);
        pending2.setApprovalStatus("PENDING");
        NoteReport approved = reportWith(1, 0, 1, 0);

        javafx.scene.control.Label lbl = invokePendingLabelUpdater(
            "updatePendingApprovalLabel", "lblPendingApproval", List.of(pending1, pending2, approved));

        assertTrue(lbl.isVisible());
        assertTrue(lbl.isManaged());
        assertTrue(lbl.getText().contains("2"), "expected count of 2 in: " + lbl.getText());
    }

    @Test
    void updatePendingGlpiLabelHiddenWhenNothingIsPending() throws Exception {
        NoteReport synced = reportWith(1, 0, 1, 0);
        javafx.scene.control.Label lbl = invokePendingLabelUpdater(
            "updatePendingGlpiLabel", "lblPendingGlpi", List.of(synced));
        assertFalse(lbl.isVisible());
        assertFalse(lbl.isManaged());
    }

    // A note still awaiting approval (or rejected) isn't a real GLPI-sync candidate yet — its
    // pending items must not count until an admin approves it. Direct user requirement, mirrored
    // from PrestamoHistoryController's own updatePendingReturnsLabel() exclusion.
    @Test
    void updatePendingGlpiLabelExcludesNotesNotYetApproved() throws Exception {
        NoteReport approvedPending = reportWith(1, 1, 0, 0);
        NoteReport stillAwaitingApproval = reportWith(1, 1, 0, 0);
        stillAwaitingApproval.setApprovalStatus("PENDING");
        NoteReport rejected = reportWith(1, 1, 0, 0);
        rejected.setApprovalStatus("REJECTED");

        javafx.scene.control.Label lbl = invokePendingLabelUpdater("updatePendingGlpiLabel",
            "lblPendingGlpi", List.of(approvedPending, stillAwaitingApproval, rejected));

        assertTrue(lbl.getText().contains("1"), "only the APPROVED note should count: " + lbl.getText());
    }
}
