package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.controllers.HistoryController;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryControllerTest {

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

    @Test
    void computeRowStyleNoAssetsReturnsNeutralColor() throws Exception {
        assertEquals("-fx-background-color: #f1f5f9;", computeRowStyle(reportWith(0, 0, 0, 0)));
    }

    @Test
    void computeRowStyleAllPendingReturnsSolidOrange() throws Exception {
        assertEquals("-fx-background-color: rgba(251,146,60,0.18);", computeRowStyle(reportWith(3, 3, 0, 0)));
    }

    @Test
    void computeRowStyleAllSyncedReturnsSolidGreen() throws Exception {
        assertEquals("-fx-background-color: rgba(34,197,94,0.18);", computeRowStyle(reportWith(3, 0, 3, 0)));
    }

    @Test
    void computeRowStyleAllRejectedReturnsSolidRed() throws Exception {
        assertEquals("-fx-background-color: rgba(239,68,68,0.18);", computeRowStyle(reportWith(3, 0, 0, 3)));
    }

    // Same bug as glpiStatusLabelAllNaAssetsReturnsDash — the old code fell through to a
    // zero-color-stop "linear-gradient(to right);" for this case instead of the flat neutral
    // color used for "nothing GLPI-tracked" everywhere else.
    @Test
    void computeRowStyleAllNaAssetsReturnsNeutralColor() throws Exception {
        assertEquals("-fx-background-color: #f1f5f9;", computeRowStyle(reportWith(1, 0, 0, 0)));
    }

    @Test
    void computeRowStyleMixedReturnsGradientSplitByShare() throws Exception {
        String style = computeRowStyle(reportWith(2, 1, 1, 0));
        assertTrue(style.startsWith("-fx-background-color: linear-gradient(to right"));
        assertTrue(style.contains("rgba(251,146,60,0.25) 0.00%"));
        assertTrue(style.contains("rgba(251,146,60,0.25) 50.00%"));
        assertTrue(style.contains("rgba(34,197,94,0.25) 50.00%"));
        assertTrue(style.contains("rgba(34,197,94,0.25) 100.00%"));
        assertTrue(style.endsWith(");"));
    }

    // ── toDisplayName ─────────────────────────────────────────────────────────

    @Test
    void toDisplayNameMapsRawProfileTypesToNiceNames() throws Exception {
        assertEquals("Entrega", toDisplayName("ENTREGA"));
        assertEquals("Devolución", toDisplayName("DEVOLUCIÓN"));
        assertEquals("Préstamo", toDisplayName("PRÉSTAMO"));
        assertEquals("Fin de contrato", toDisplayName("ENTREGA PERMANENTE"));
        assertEquals("Entrega - Proveedor", toDisplayName("Entrega - Proveedor"));
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
    void expandProfileTypeLabelsHandlesFinDeContratoAliases() throws Exception {
        List<String> expanded = expandProfileTypeLabels(new LinkedHashSet<>(Set.of("Fin de Contrato")));
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
}
