package com.bunshock.note_app_for_it_frontend.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;

public class NoteGenerationService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final TemplateEngine engine = new TemplateEngine();

    public String generateUserNote(String profileType,
                                   String userName, String userDni, String userEmail,
                                   String motivo,
                                   String technicianName, String technicianDni, String sede,
                                   String failureCause, String failureDetails, String areaEvento,
                                   List<AssetItem> assets, List<CountableItem> countables,
                                   String observations) throws IOException {
        String template = loadTemplate(resolveTemplateName(profileType));

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("TEMPLATE_NAME", toDisplayName(profileType));
        tokens.put("DATE", LocalDateTime.now().format(DT_FMT));
        tokens.put("NAME", userName);
        tokens.put("DNI", userDni);
        tokens.put("EMAIL", userEmail);
        tokens.put("REASON", motivo != null ? motivo : "");
        // Préstamo captures a return date through this same parameter instead of a reason;
        // only prestamo.html declares this token, so it's harmless for every other template.
        tokens.put("EXPECTED_RETURN_DATE", motivo != null ? motivo : "");
        tokens.put("TECHNICIAN_NAME", technicianName);
        tokens.put("TECHNICIAN_DNI", technicianDni);
        tokens.put("SEDE", sede != null ? sede : "");
        tokens.put("OBSERVATIONS", observations);

        Map<String, List<Map<String, String>>> loops = new LinkedHashMap<>();
        loops.put("ASSET_ITEMS", buildAssetItemTokens(assets));
        loops.put("HAS_ASSET_ITEMS", presenceFlag(!assets.isEmpty()));
        loops.put("COUNTABLE_ITEMS", buildCountableItemTokens(countables));
        loops.put("HAS_COUNTABLE_ITEMS", presenceFlag(!countables.isEmpty()));
        loops.put("FAILURE", failureLoop(failureCause, failureDetails));
        loops.put("HAS_AREA_EVENT", areaEventLoop(areaEvento));

        return engine.render(template, tokens, loops);
    }

    public String generateProviderNote(String providerName, String cuit,
                                       String responsibleName, String responsibleDni,
                                       String motivo,
                                       String technicianName, String technicianDni, String sede,
                                       List<AssetItem> assets, List<CountableItem> countables,
                                       String observations) throws IOException {
        String template = loadTemplate("proveedor.html");

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("TEMPLATE_NAME", "Entrega - Proveedor");
        tokens.put("DATE", LocalDateTime.now().format(DT_FMT));
        tokens.put("COMPANY_NAME", providerName);
        tokens.put("RESPONSIBLE_NAME", responsibleName);
        tokens.put("RESPONSIBLE_DNI", responsibleDni);
        tokens.put("REASON", motivo != null ? motivo : "");
        tokens.put("TECHNICIAN_NAME", technicianName);
        tokens.put("TECHNICIAN_DNI", technicianDni);
        tokens.put("SEDE", sede != null ? sede : "");
        tokens.put("OBSERVATIONS", observations);

        Map<String, List<Map<String, String>>> loops = new LinkedHashMap<>();
        loops.put("ASSET_ITEMS", buildAssetItemTokens(assets));
        loops.put("HAS_ASSET_ITEMS", presenceFlag(!assets.isEmpty()));
        loops.put("COUNTABLE_ITEMS", buildCountableItemTokens(countables));
        loops.put("HAS_COUNTABLE_ITEMS", presenceFlag(!countables.isEmpty()));
        loops.put("HAS_CUIT", cuitLoop(cuit));

        return engine.render(template, tokens, loops);
    }

    // Source Sede, destination label/address/recipients, technician, and item lists — a Remito
    // has no Motivo/user-recipient/failure/area-evento concepts, unlike generateUserNote().
    public String generateRemitoNote(String sourceSede,
                                     String destinationLabel, String address, String recipients,
                                     String technicianName, String technicianDni,
                                     List<AssetItem> assets, List<CountableItem> countables,
                                     String observations) throws IOException {
        String template = loadTemplate("remito.html");

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("TEMPLATE_NAME", "Remito de Envío");
        tokens.put("DATE", LocalDateTime.now().format(DT_FMT));
        tokens.put("SEDE", sourceSede != null ? sourceSede : "");
        tokens.put("DESTINATION_LABEL", destinationLabel != null ? destinationLabel : "");
        tokens.put("ADDRESS", address != null ? address : "");
        tokens.put("RECIPIENTS", recipients != null ? recipients : "");
        tokens.put("TECHNICIAN_NAME", technicianName);
        tokens.put("TECHNICIAN_DNI", technicianDni);
        tokens.put("OBSERVATIONS", observations);

        Map<String, List<Map<String, String>>> loops = new LinkedHashMap<>();
        loops.put("ASSET_ITEMS", buildAssetItemTokens(assets));
        loops.put("HAS_ASSET_ITEMS", presenceFlag(!assets.isEmpty()));
        loops.put("COUNTABLE_ITEMS", buildCountableItemTokens(countables));
        loops.put("HAS_COUNTABLE_ITEMS", presenceFlag(!countables.isEmpty()));

        return engine.render(template, tokens, loops);
    }

    /** Re-renders a stored NoteReport back into HTML for the history detail view. */
    public String generateFromStoredReport(NoteReport report) throws IOException {
        String templateName = report.getDestinationLabel() != null
            ? "remito.html"
            : report.getProviderName() != null
                ? "proveedor.html"
                : resolveTemplateName(report.getProfileType());
        String template = loadTemplate(templateName);

        String dateStr = report.getCreatedAt() != null
            ? report.getCreatedAt().format(DT_FMT) : "";
        String motivo = orEmpty(report.getMotivo());

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("TEMPLATE_NAME", toDisplayName(report.getProfileType()));
        tokens.put("DATE", dateStr);
        tokens.put("NAME",   orEmpty(report.getUserName()));
        tokens.put("DNI",    orEmpty(report.getUserDni()));
        tokens.put("EMAIL",  orEmpty(report.getUserEmail()));
        tokens.put("REASON", motivo);
        tokens.put("EXPECTED_RETURN_DATE", motivo);
        tokens.put("TECHNICIAN_NAME", orEmpty(report.getAuthorName()));
        tokens.put("TECHNICIAN_DNI",  orEmpty(report.getAuthorDni()));
        tokens.put("SEDE", orEmpty(report.getSede()));
        tokens.put("OBSERVATIONS", orEmpty(report.getObservations()));
        tokens.put("COMPANY_NAME", orEmpty(report.getProviderName()));
        tokens.put("RESPONSIBLE_NAME", orEmpty(report.getResponsibleName()));
        tokens.put("RESPONSIBLE_DNI", orEmpty(report.getResponsibleDni()));
        tokens.put("DESTINATION_LABEL", orEmpty(report.getDestinationLabel()));
        tokens.put("ADDRESS", orEmpty(report.getAddress()));
        tokens.put("RECIPIENTS", orEmpty(report.getRecipients()));

        List<Map<String, String>> assetTokens = new ArrayList<>();
        List<Map<String, String>> countableTokens = new ArrayList<>();
        if (report.getItems() != null) {
            for (NoteReportItem item : report.getItems()) {
                Map<String, String> t = new LinkedHashMap<>();
                String details = orEmpty(item.getObservations());
                t.put("TYPE",  orEmpty(item.getTypeName()));
                t.put("BRAND", orEmpty(item.getBrandName()));
                t.put("MODEL", orEmpty(item.getModelName()));
                t.put("DETAILS", details);
                if (item.isAsset()) {
                    String serial = orEmpty(item.getSerialNumber());
                    String af = orEmpty(item.getAf());
                    t.put("SERIAL",    serial);
                    t.put("ASSET_TAG", af);
                    t.put("META", assetMeta(serial, af, details));
                    assetTokens.add(t);
                } else {
                    int quantity = item.getQuantity();
                    t.put("QUANTITY", String.valueOf(quantity));
                    t.put("META", countableMeta(quantity, details));
                    countableTokens.add(t);
                }
            }
        }

        Map<String, List<Map<String, String>>> loops = new LinkedHashMap<>();
        loops.put("ASSET_ITEMS", assetTokens);
        loops.put("HAS_ASSET_ITEMS", presenceFlag(!assetTokens.isEmpty()));
        loops.put("COUNTABLE_ITEMS", countableTokens);
        loops.put("HAS_COUNTABLE_ITEMS", presenceFlag(!countableTokens.isEmpty()));
        loops.put("FAILURE", failureLoop(report.getFailureCause(), report.getFailureDetails()));
        loops.put("HAS_AREA_EVENT", areaEventLoop(report.getAreaEvento()));
        loops.put("HAS_CUIT", cuitLoop(report.getCuit()));

        return engine.render(template, tokens, loops);
    }

    private List<Map<String, String>> areaEventLoop(String areaEvento) {
        if (areaEvento == null || areaEvento.isBlank()) return List.of();
        return List.of(Map.of("AREA_EVENT", areaEvento));
    }

    // CUIT is optional ("Incluir CUIT" checkbox in ProviderNoteController) — same nested-loop
    // shape as areaEventLoop() above, and for the same reason: expandLoops() only replaces tokens
    // from the loop's own per-entry map, not the top-level tokens map, so CUIT can't be a bare
    // top-level token referenced inside a conditional block (see NoteGenerationService's own
    // "Nested loops" doc in CLAUDE.md for the full gotcha).
    private List<Map<String, String>> cuitLoop(String cuit) {
        if (cuit == null || cuit.isBlank()) return List.of();
        return List.of(Map.of("CUIT", cuit));
    }

    private List<Map<String, String>> failureLoop(String failureCause, String failureDetails) {
        if (failureCause == null || failureCause.isBlank()) return List.of();
        // Details are optional (only the cause combobox is mandatory) — the " — " separator
        // is only meaningful when there's actually a details string to attach it to; otherwise
        // it would print as a dangling "No enciende — " with nothing after it.
        String text = failureDetails != null && !failureDetails.isBlank()
            ? failureCause + " — " + failureDetails
            : failureCause;
        return List.of(Map.of("FAILURE_TEXT", text));
    }

    private List<Map<String, String>> buildAssetItemTokens(List<AssetItem> assets) {
        List<Map<String, String>> result = new ArrayList<>();
        for (AssetItem a : assets) {
            Map<String, String> t = new LinkedHashMap<>();
            String serial = a.getSerial().get();
            String af = a.getAf().get();
            String details = a.getObservations().get();
            t.put("TYPE", a.getType().get());
            t.put("BRAND", a.getBrand().get());
            t.put("MODEL", a.getModel().get());
            t.put("SERIAL", serial);
            t.put("ASSET_TAG", af);
            t.put("DETAILS", details);
            t.put("META", assetMeta(serial, af, details));
            result.add(t);
        }
        return result;
    }

    private List<Map<String, String>> buildCountableItemTokens(List<CountableItem> countables) {
        List<Map<String, String>> result = new ArrayList<>();
        for (CountableItem c : countables) {
            Map<String, String> t = new LinkedHashMap<>();
            int quantity = c.getQuantity().get();
            String details = c.getObservations().get();
            t.put("TYPE", c.getType().get());
            t.put("BRAND", c.getBrand().get());
            t.put("MODEL", c.getModel().get());
            t.put("QUANTITY", String.valueOf(quantity));
            t.put("DETAILS", details);
            t.put("META", countableMeta(quantity, details));
            result.add(t);
        }
        return result;
    }

    // Builds the compact list layout's right-hand "meta" line (Option B) — only the
    // pieces that actually have a value are joined, so a blank S/N (Sin S/N) or empty Detalles
    // never leaves a dangling separator behind. Same "combine conditionally in Java, not in the
    // template" precedent as failureLoop()'s FAILURE_TEXT above.
    private String assetMeta(String serial, String af, String details) {
        return joinMeta(
            serial != null && !serial.isBlank() ? "S/N: " + serial : null,
            af != null && !af.isBlank() ? "A/F: " + af : null,
            details);
    }

    private String countableMeta(int quantity, String details) {
        return joinMeta("Cantidad: " + quantity, details);
    }

    private String joinMeta(String... parts) {
        List<String> nonBlank = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) nonBlank.add(p);
        }
        return String.join(" · ", nonBlank);
    }

    // Backs the "HAS_X" nested-loop wrapper keys (see TemplateEngine's nested-loop support) —
    // a single dummy entry when present, or an empty list, so {{#HAS_X}}...{{/HAS_X}} can wrap
    // a whole table (header included) and make it vanish entirely when that item list is empty.
    private List<Map<String, String>> presenceFlag(boolean present) {
        return present ? List.of(Map.of()) : List.of();
    }

    private String toDisplayName(String profileType) {
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

    private String resolveTemplateName(String profileType) {
        if (profileType == null) return "entrega.html";
        return switch (profileType.toUpperCase()) {
            case "DEVOLUCIÓN", "DEVOLUCION"               -> "devolucion.html";
            case "ENTREGA PERMANENTE", "FIN DE CONTRATO"  -> "entrega - fin de contrato.html";
            case "PRÉSTAMO", "PRESTAMO"                   -> "prestamo.html";
            default                                       -> "entrega.html";
        };
    }

    private String loadTemplate(String name) throws IOException {
        String path = "/com/bunshock/note_app_for_it_frontend/templates/" + name;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Template not found: " + path);
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return embedLogoBase64(html);
        }
    }

    // WebView.loadContent() has no base URL, so relative src paths never resolve.
    // Replace the logo src attribute with an inline Base64 data URL.
    private String embedLogoBase64(String html) {
        String logoPath = "/com/bunshock/note_app_for_it_frontend/images/logo.png";
        try (InputStream is = getClass().getResourceAsStream(logoPath)) {
            if (is == null || !html.contains("src=\"logo.jpg\"")) return html;
            String b64 = Base64.getEncoder().encodeToString(is.readAllBytes());
            return html.replace("src=\"logo.jpg\"", "src=\"data:image/png;base64," + b64 + "\"");
        } catch (IOException e) {
            return html;
        }
    }

    private String orEmpty(String s) { return s != null ? s : ""; }
}
