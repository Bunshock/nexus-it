package com.bunshock.note_app_for_it_frontend;

import java.time.LocalDateTime;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.services.NoteGenerationService;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoteGenerationServiceTest {

    private final NoteGenerationService service = new NoteGenerationService();

    private List<AssetItem> oneAsset() {
        return List.of(new AssetItem("NOTEBOOK", "DELL", "LATITUDE", "Sin detalles", "SN123", "AF-001", 1, 1, 1));
    }

    private List<CountableItem> oneCountable(int qty) {
        return List.of(new CountableItem("MOUSE", "GENIUS", "DX-120", qty, "Nuevo", 2, 2, 2));
    }

    @Test
    void generatesEntregaNoteWithUserItemAndTechnicianTokens() throws Exception {
        String html = service.generateUserNote("ENTREGA", "Juan Perez", "30111222", "juan@test.com",
            "Alta", "Marcos Tecnico", "27555111", "Campus Test", null, null, null,
            oneAsset(), oneCountable(1), "Sin observaciones");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Juan Perez"));
        assertTrue(html.contains("30111222"));
        // Email is captured (for AD lookup / SMTP) but intentionally never printed on the note.
        assertFalse(html.contains("juan@test.com"));
        assertTrue(html.contains("Alta"));
        assertTrue(html.contains("Marcos Tecnico"));
        assertTrue(html.contains("27555111"));
        assertTrue(html.contains("Campus Test"));
        assertTrue(html.contains("NOTEBOOK"));
        assertTrue(html.contains("SN123"));
        assertTrue(html.contains("AF-001"));
        assertTrue(html.contains("note-table"));
    }

    @Test
    void generatesDevolucionNoteWithoutFailureBlockWhenNoCause() throws Exception {
        String html = service.generateUserNote("Devolución", "Ana Diaz", "28999111", "ana@test.com",
            "Fin de préstamo", "Marcos Tecnico", "27555111", "Campus Test", null, null, null,
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("note-table"));
        assertTrue(html.contains("quien devuelve") || html.contains("Quien devuelve"));
        assertTrue(html.contains("Ana Diaz"));
        assertTrue(html.contains("28999111"));
        assertFalse(html.contains("ana@test.com"));
        assertTrue(html.contains("Fin de préstamo"));
        assertTrue(html.contains("Marcos Tecnico"));
        assertTrue(html.contains("27555111"));
        assertFalse(html.contains("Detalles de Falla"));
    }

    @Test
    void generatesDevolucionNoteWithFailureBlockWhenCausePresent() throws Exception {
        String html = service.generateUserNote("Devolución", "Ana Diaz", "28999111", "ana@test.com",
            "Falla", "Marcos Tecnico", "27555111", "Campus Test", "No enciende", "El equipo no responde al presionar el botón de encendido", null,
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Detalles de Falla"));
        assertTrue(html.contains("No enciende"));
        assertTrue(html.contains("El equipo no responde al presionar el botón de encendido"));
    }

    @Test
    void generatesDevolucionNoteWithFailureCauseOnlyShowsNoDanglingSeparator() throws Exception {
        String html = service.generateUserNote("Devolución", "Ana Diaz", "28999111", "ana@test.com",
            "Falla", "Marcos Tecnico", "27555111", "Campus Test", "No enciende", null, null,
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Detalles de Falla"));
        assertTrue(html.contains("No enciende"));
        assertFalse(html.contains("No enciende —"));
    }

    @Test
    void generatesFinDeContratoNoteFromDedicatedTemplate() throws Exception {
        // "ENTREGA PERMANENTE" is the literal ToggleButton text the live UI passes as
        // profileType for this note type (see UserNoteView.fxml / UserNoteController).
        String html = service.generateUserNote("ENTREGA PERMANENTE", "Luis Gomez", "27888555", "luis@test.com",
            "Baja de contrato", "Marcos Tecnico", "27555111", "Campus Test", null, null, null,
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Fin de contrato"));
        assertTrue(html.contains("Luis Gomez"));
        assertTrue(html.contains("Baja de contrato"));
        assertTrue(html.contains("Marcos Tecnico"));
    }

    @Test
    void generatesPrestamoNoteWithExpectedReturnDateBelowItemsAndAboveObservations() throws Exception {
        String html = service.generateUserNote("PRÉSTAMO", "Marta Ruiz", "26777444", "marta@test.com",
            "20/08/2026", "Marcos Tecnico", "27555111", "Campus Test", null, null, null,
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Marta Ruiz"));
        assertTrue(html.contains("26777444"));
        assertFalse(html.contains("marta@test.com"));
        assertTrue(html.contains("20/08/2026"));
        assertTrue(html.contains("Préstamo"));
        assertTrue(html.contains("Marcos Tecnico"));

        int itemsIdx = html.indexOf("<table class=\"note-table\">");
        int returnDateIdx = html.indexOf("20/08/2026");
        int observationsIdx = html.indexOf("Observaciones");
        assertTrue(itemsIdx < returnDateIdx, "Expected return date should appear below the item table");
        assertTrue(returnDateIdx < observationsIdx, "Expected return date should appear above Observaciones");
    }

    @Test
    void prestamoNoteShowsAreaEventoOnlyWhenProvided() throws Exception {
        String withAreaEvento = service.generateUserNote("PRÉSTAMO", "Marta Ruiz", "26777444", "marta@test.com",
            "20/08/2026", "Marcos Tecnico", "27555111", "Campus Test", null, null, "Área de Sistemas",
            oneAsset(), List.of(), "");
        String withoutAreaEvento = service.generateUserNote("PRÉSTAMO", "Marta Ruiz", "26777444", "marta@test.com",
            "20/08/2026", "Marcos Tecnico", "27555111", "Campus Test", null, null, null,
            oneAsset(), List.of(), "");

        assertFalse(withAreaEvento.contains("{{"));
        assertTrue(withAreaEvento.contains("Área de Sistemas"));
        assertTrue(withAreaEvento.contains("Área / Evento"));

        assertFalse(withoutAreaEvento.contains("{{"));
        assertFalse(withoutAreaEvento.contains("Área / Evento"));
    }

    @Test
    void countableItemAlwaysShowsQuantityInDedicatedColumn() throws Exception {
        String single = service.generateUserNote("ENTREGA", "Juan Perez", "1", "j@test.com",
            null, "Marcos Tecnico", "27555111", "Campus Test", null, null, null,
            List.of(), oneCountable(1), "");
        String multiple = service.generateUserNote("ENTREGA", "Juan Perez", "1", "j@test.com",
            null, "Marcos Tecnico", "27555111", "Campus Test", null, null, null,
            List.of(), oneCountable(5), "");

        assertTrue(single.contains("<td>MOUSE</td><td>GENIUS</td><td>DX-120</td><td>1</td>"));
        assertTrue(multiple.contains("<td>MOUSE</td><td>GENIUS</td><td>DX-120</td><td>5</td>"));
    }

    @Test
    void generatesProviderNoteWithAllCapturedFieldsAndTechnician() throws Exception {
        String html = service.generateProviderNote("Proveedor SA", "30-12345678-9",
            "Carlos Ruiz", "25777333", "Alta", "Marcos Tecnico", "27555111", "Campus Test",
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("NOTEBOOK"));
        assertTrue(html.contains("Alta"));
        assertTrue(html.contains("Proveedor SA"));
        assertTrue(html.contains("30-12345678-9"));
        assertTrue(html.contains("Carlos Ruiz"));
        assertTrue(html.contains("25777333"));
        assertTrue(html.contains("Marcos Tecnico"));
        assertTrue(html.contains("27555111"));

        int itemsIdx = html.indexOf("<table class=\"note-table\">");
        int motivoIdx = html.indexOf("Alta");
        assertTrue(itemsIdx < motivoIdx, "Motivo should appear below the item table");
    }

    @Test
    void generatesProviderNoteWithoutOptionalResponsibleShowsEmptyLeftBox() throws Exception {
        String html = service.generateProviderNote("Proveedor SA", "30-12345678-9",
            "", "", "Alta", "Marcos Tecnico", "27555111", "Campus Test",
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Proveedor SA"));
        assertTrue(html.contains("Marcos Tecnico"));
    }

    @Test
    void generatesRemitoNoteWithDestinatarioRemitenteAndItemsNoObservationsOrSignatures() throws Exception {
        String html = service.generateRemitoNote(
            "Maria Meossi", "Coordinador CAU", "(CAU) Santiago del Estero - Jujuy N° 8 (CP: 4200)",
            "Wladimir Naar",
            "Soporte IT", "Campus Córdoba - Calle de los Latinos N°8555 – B° Los Bulevares",
            "Campus Test",
            oneAsset(), oneCountable(2));

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Maria Meossi"));
        assertTrue(html.contains("Coordinador CAU"));
        assertTrue(html.contains("(CAU) Santiago del Estero - Jujuy N° 8 (CP: 4200)"));
        assertTrue(html.contains("Wladimir Naar"));
        assertTrue(html.contains("Soporte IT"));
        assertTrue(html.contains("Campus Córdoba"));
        assertTrue(html.contains("Remito de Envío"));
        assertTrue(html.contains("NOTEBOOK"));
        assertTrue(html.contains("MOUSE"));

        // No signatures, no DNI, no Observaciones Generales — matching the physical source
        // document exactly, per explicit user direction (see CLAUDE.md's "Remito de Envío" entry).
        assertFalse(html.contains("signature-box"));
        assertFalse(html.contains("OBSERVACIONES"));
    }

    @Test
    void generateFromStoredReportRendersRemitoNoteWithDestinatarioAndRemitenteFields() throws Exception {
        NoteReport report = new NoteReport();
        report.setProfileType("Remito de Envío");
        report.setCreatedAt(LocalDateTime.of(2026, 7, 20, 9, 0));
        report.setDestinatarioName("Luis Morillo");
        report.setDestinatarioArea("Soporte IT");
        report.setDestinatarioSede("Vicente López - Av. del Libertador 107 - Buenos Aires (CP: 1638)");
        report.setRemitenteName("Wladimir Naar");
        report.setRemitenteArea("Soporte IT");
        report.setRemitenteSede("Campus Córdoba - Calle de los Latinos N°8555 – B° Los Bulevares");
        // The generating technician is a different person from Remitente — captured for
        // traceability, but never rendered on remito.html.
        report.setAuthorName("Marcos Tecnico");
        report.setAuthorDni("27555111");

        NoteReportItem asset = new NoteReportItem();
        asset.setTypeName("HEADSET");
        asset.setBrandName("TRUST");
        asset.setModelName("AYDA");
        asset.setSerialNumber("N/A");
        asset.setAsset(true);
        report.setItems(List.of(asset));

        String html = service.generateFromStoredReport(report);

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Luis Morillo"));
        assertTrue(html.contains("Vicente López"));
        assertTrue(html.contains("Wladimir Naar"));
        assertTrue(html.contains("Remito de Envío"));
        assertTrue(html.contains("HEADSET"));
        assertFalse(html.contains("27555111"), "Remito has no DNI on the printed note");
        assertFalse(html.contains("Marcos Tecnico"),
            "The generating technician (author) is a different person from Remitente and must never be rendered");
    }

    @Test
    void generateFromStoredReportRendersRecipientReportWithTechnician() throws Exception {
        NoteReport report = new NoteReport();
        report.setProfileType("ENTREGA");
        report.setCreatedAt(LocalDateTime.of(2026, 1, 15, 10, 30));
        report.setUserName("Maria Lopez");
        report.setUserDni("32444555");
        report.setUserEmail("maria@test.com");
        report.setMotivo("Alta");
        report.setAuthorName("Marcos Tecnico");
        report.setAuthorDni("27555111");

        NoteReportItem asset = new NoteReportItem();
        asset.setTypeName("MONITOR");
        asset.setBrandName("SAMSUNG");
        asset.setModelName("S22");
        asset.setSerialNumber("SNX1");
        asset.setAf("AF-777");
        asset.setAsset(true);

        NoteReportItem countable = new NoteReportItem();
        countable.setTypeName("CABLE HDMI");
        countable.setAsset(false);
        countable.setQuantity(3);

        report.setItems(List.of(asset, countable));

        String html = service.generateFromStoredReport(report);

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Maria Lopez"));
        assertTrue(html.contains("32444555"));
        assertFalse(html.contains("maria@test.com"));
        assertTrue(html.contains("Alta"));
        assertTrue(html.contains("Marcos Tecnico"));
        assertTrue(html.contains("27555111"));
        assertTrue(html.contains("15/01/2026 10:30"));
        assertTrue(html.contains("MONITOR"));
        assertTrue(html.contains("SNX1"));
        assertTrue(html.contains("AF-777"));
        assertTrue(html.contains("<td>CABLE HDMI</td><td></td><td></td><td>3</td><td></td>"));
    }

    @Test
    void generateFromStoredReportRendersDevolucionWithFailureDetails() throws Exception {
        NoteReport report = new NoteReport();
        report.setProfileType("DEVOLUCIÓN");
        report.setCreatedAt(LocalDateTime.of(2026, 4, 1, 11, 0));
        report.setUserName("Ana Diaz");
        report.setUserDni("28999111");
        report.setMotivo("Falla");
        report.setFailureCause("No enciende");
        report.setFailureDetails("Pantalla no responde");
        report.setAuthorName("Marcos Tecnico");
        report.setAuthorDni("27555111");
        report.setItems(List.of());

        String html = service.generateFromStoredReport(report);

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Detalles de Falla"));
        assertTrue(html.contains("No enciende"));
        assertTrue(html.contains("Pantalla no responde"));
    }

    @Test
    void generateFromStoredReportRendersProviderReport() throws Exception {
        NoteReport report = new NoteReport();
        report.setProfileType("Entrega - Proveedor");
        report.setCreatedAt(LocalDateTime.of(2026, 2, 1, 9, 0));
        report.setProviderName("Proveedor SA");
        report.setCuit("30-12345678-9");
        report.setMotivo("Alta");
        report.setResponsibleName("Carlos Ruiz");
        report.setResponsibleDni("25777333");
        report.setAuthorName("Marcos Tecnico");
        report.setAuthorDni("27555111");
        report.setItems(List.of());

        String html = service.generateFromStoredReport(report);

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Proveedor SA"));
        assertTrue(html.contains("30-12345678-9"));
        assertTrue(html.contains("Alta"));
        assertTrue(html.contains("Carlos Ruiz"));
        assertTrue(html.contains("25777333"));
        assertTrue(html.contains("Marcos Tecnico"));
        assertTrue(html.contains("01/02/2026 09:00"));
    }

    @Test
    void generateFromStoredReportRendersPrestamoReportWithExpectedReturnDate() throws Exception {
        NoteReport report = new NoteReport();
        report.setProfileType("PRÉSTAMO");
        report.setCreatedAt(LocalDateTime.of(2026, 3, 1, 8, 0));
        report.setUserName("Marta Ruiz");
        report.setUserDni("26777444");
        report.setUserEmail("marta@test.com");
        report.setMotivo("20/08/2026");
        report.setAuthorName("Marcos Tecnico");
        report.setAuthorDni("27555111");
        report.setItems(List.of());

        String html = service.generateFromStoredReport(report);

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Marta Ruiz"));
        assertTrue(html.contains("20/08/2026"));
        assertTrue(html.contains("Marcos Tecnico"));
    }

    // Regression test: generateFromStoredReport() used to hardcode OBSERVATIONS to "" for every
    // report regardless of what was actually saved — this note-level field is now persisted on
    // NOTE_REPORT and must round-trip back into the reprinted/reopened HTML, not just the
    // original at-creation-time render.
    @Test
    void generateFromStoredReportRendersPersistedObservationsGenerales() throws Exception {
        NoteReport report = new NoteReport();
        report.setProfileType("ENTREGA");
        report.setCreatedAt(LocalDateTime.of(2026, 1, 15, 10, 30));
        report.setUserName("Maria Lopez");
        report.setUserDni("32444555");
        report.setMotivo("Alta");
        report.setAuthorName("Marcos Tecnico");
        report.setAuthorDni("27555111");
        report.setObservations("Se entrega con cargador adicional");
        report.setItems(List.of());

        String html = service.generateFromStoredReport(report);

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Se entrega con cargador adicional"));
    }

    // Sede is stamped onto NOTE_REPORT at generation time and must round-trip back into the
    // reprinted/reopened HTML, same regression class as OBSERVATIONS above.
    @Test
    void generateFromStoredReportRendersPersistedSede() throws Exception {
        NoteReport report = new NoteReport();
        report.setProfileType("ENTREGA");
        report.setCreatedAt(LocalDateTime.of(2026, 1, 15, 10, 30));
        report.setUserName("Maria Lopez");
        report.setUserDni("32444555");
        report.setMotivo("Alta");
        report.setAuthorName("Marcos Tecnico");
        report.setAuthorDni("27555111");
        report.setSede("Campus Test");
        report.setItems(List.of());

        String html = service.generateFromStoredReport(report);

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Campus Test"));
    }
}
