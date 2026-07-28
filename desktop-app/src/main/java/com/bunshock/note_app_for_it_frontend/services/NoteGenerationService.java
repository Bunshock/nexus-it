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
        tokens.put("CUIT", cuit);
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

        return engine.render(template, tokens, loops);
    }

    /** Re-renders a stored NoteReport back into HTML for the history detail view. */
    public String generateFromStoredReport(NoteReport report) throws IOException {
        String templateName = report.getProviderName() != null
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
        tokens.put("CUIT", orEmpty(report.getCuit()));
        tokens.put("RESPONSIBLE_NAME", orEmpty(report.getResponsibleName()));
        tokens.put("RESPONSIBLE_DNI", orEmpty(report.getResponsibleDni()));

        List<Map<String, String>> assetTokens = new ArrayList<>();
        List<Map<String, String>> countableTokens = new ArrayList<>();
        if (report.getItems() != null) {
            for (NoteReportItem item : report.getItems()) {
                Map<String, String> t = new LinkedHashMap<>();
                t.put("TYPE",  orEmpty(item.getTypeName()));
                t.put("BRAND", orEmpty(item.getBrandName()));
                t.put("MODEL", orEmpty(item.getModelName()));
                t.put("DETAILS", orEmpty(item.getObservations()));
                if (item.isAsset()) {
                    t.put("SERIAL",    orEmpty(item.getSerialNumber()));
                    t.put("ASSET_TAG", orEmpty(item.getAf()));
                    assetTokens.add(t);
                } else {
                    t.put("QUANTITY", String.valueOf(item.getQuantity()));
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

        return engine.render(template, tokens, loops);
    }

    private List<Map<String, String>> areaEventLoop(String areaEvento) {
        if (areaEvento == null || areaEvento.isBlank()) return List.of();
        return List.of(Map.of("AREA_EVENT", areaEvento));
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
            t.put("TYPE", a.getType().get());
            t.put("BRAND", a.getBrand().get());
            t.put("MODEL", a.getModel().get());
            t.put("SERIAL", a.getSerial().get());
            t.put("ASSET_TAG", a.getAf().get());
            t.put("DETAILS", a.getObservations().get());
            result.add(t);
        }
        return result;
    }

    private List<Map<String, String>> buildCountableItemTokens(List<CountableItem> countables) {
        List<Map<String, String>> result = new ArrayList<>();
        for (CountableItem c : countables) {
            Map<String, String> t = new LinkedHashMap<>();
            t.put("TYPE", c.getType().get());
            t.put("BRAND", c.getBrand().get());
            t.put("MODEL", c.getModel().get());
            t.put("QUANTITY", String.valueOf(c.getQuantity().get()));
            t.put("DETAILS", c.getObservations().get());
            result.add(t);
        }
        return result;
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
            case "ENTREGA PERMANENTE"  -> "Fin de contrato";
            case "FIN DE CONTRATO"     -> "Fin de contrato";
            case "ENTREGA - PROVEEDOR" -> "Entrega - Proveedor";
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
