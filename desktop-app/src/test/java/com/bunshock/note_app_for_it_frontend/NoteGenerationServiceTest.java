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
        return List.of(new AssetItem("NOTEBOOK", "DELL", "LATITUDE", "Sin detalles", "SN123", "AF-001"));
    }

    private List<CountableItem> oneCountable(int qty) {
        return List.of(new CountableItem("MOUSE", "GENIUS", "DX-120", qty, "Nuevo"));
    }

    @Test
    void generatesEntregaNoteWithUserItemAndTechnicianTokens() throws Exception {
        String html = service.generateUserNote("ENTREGA", "Juan Perez", "30111222", "juan@test.com",
            "Alta", "Marcos Tecnico", "27555111", null, null,
            oneAsset(), oneCountable(1), "Sin observaciones");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Juan Perez"));
        assertTrue(html.contains("30111222"));
        // Email is captured (for AD lookup / SMTP) but intentionally never printed on the note.
        assertFalse(html.contains("juan@test.com"));
        assertTrue(html.contains("Alta"));
        assertTrue(html.contains("Marcos Tecnico"));
        assertTrue(html.contains("27555111"));
        assertTrue(html.contains("NOTEBOOK"));
        assertTrue(html.contains("SN123"));
        assertTrue(html.contains("AF-001"));
        assertTrue(html.contains("item-property"));
    }

    @Test
    void generatesDevolucionNoteWithoutFailureBlockWhenNoCause() throws Exception {
        String html = service.generateUserNote("Devolución", "Ana Diaz", "28999111", "ana@test.com",
            "Fin de préstamo", "Marcos Tecnico", "27555111", null, null,
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("item-value"));
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
            "Falla", "Marcos Tecnico", "27555111", "No enciende", "El equipo no responde al presionar el botón de encendido",
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Detalles de Falla"));
        assertTrue(html.contains("No enciende"));
        assertTrue(html.contains("El equipo no responde al presionar el botón de encendido"));
    }

    @Test
    void generatesFinDeContratoNoteFromDedicatedTemplate() throws Exception {
        // "ENTREGA PERMANENTE" is the literal ToggleButton text the live UI passes as
        // profileType for this note type (see UserNoteView.fxml / UserNoteController).
        String html = service.generateUserNote("ENTREGA PERMANENTE", "Luis Gomez", "27888555", "luis@test.com",
            "Baja de contrato", "Marcos Tecnico", "27555111", null, null,
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Fin de contrato"));
        assertTrue(html.contains("Luis Gomez"));
        assertTrue(html.contains("Baja de contrato"));
        assertTrue(html.contains("Marcos Tecnico"));
    }

    @Test
    void generatesPrestamoNoteWithExpectedReturnDateAboveItems() throws Exception {
        String html = service.generateUserNote("PRÉSTAMO", "Marta Ruiz", "26777444", "marta@test.com",
            "20/08/2026", "Marcos Tecnico", "27555111", null, null,
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Marta Ruiz"));
        assertTrue(html.contains("26777444"));
        assertFalse(html.contains("marta@test.com"));
        assertTrue(html.contains("20/08/2026"));
        assertTrue(html.contains("Préstamo"));
        assertTrue(html.contains("Marcos Tecnico"));

        int returnDateIdx = html.indexOf("20/08/2026");
        int itemsIdx = html.indexOf("<div class=\"items-list-container\">");
        assertTrue(returnDateIdx < itemsIdx, "Expected return date should appear above the item table");
    }

    @Test
    void countableItemShowsQuantityOnlyWhenGreaterThanOne() throws Exception {
        String single = service.generateUserNote("ENTREGA", "Juan Perez", "1", "j@test.com",
            null, "Marcos Tecnico", "27555111", null, null,
            List.of(), oneCountable(1), "");
        String multiple = service.generateUserNote("ENTREGA", "Juan Perez", "1", "j@test.com",
            null, "Marcos Tecnico", "27555111", null, null,
            List.of(), oneCountable(5), "");

        assertFalse(single.contains("Cant:"));
        assertTrue(multiple.contains("Cant: 5"));
    }

    @Test
    void generatesProviderNoteWithAllCapturedFieldsAndTechnician() throws Exception {
        String html = service.generateProviderNote("Proveedor SA", "30-12345678-9",
            "Carlos Ruiz", "25777333", "Alta", "Marcos Tecnico", "27555111",
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

        int motivoIdx = html.indexOf("Alta");
        int itemsIdx = html.indexOf("<div class=\"items-list-container\">");
        assertTrue(motivoIdx < itemsIdx, "Motivo should appear above the item table");
    }

    @Test
    void generatesProviderNoteWithoutOptionalResponsibleShowsEmptyLeftBox() throws Exception {
        String html = service.generateProviderNote("Proveedor SA", "30-12345678-9",
            "", "", "Alta", "Marcos Tecnico", "27555111",
            oneAsset(), List.of(), "");

        assertFalse(html.contains("{{"));
        assertTrue(html.contains("Proveedor SA"));
        assertTrue(html.contains("Marcos Tecnico"));
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
        assertTrue(html.contains("CABLE HDMI"));
        assertTrue(html.contains("Cant: 3"));
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
}
