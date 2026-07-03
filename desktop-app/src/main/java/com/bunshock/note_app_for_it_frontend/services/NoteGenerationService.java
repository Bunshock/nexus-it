package com.bunshock.note_app_for_it_frontend.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final TemplateEngine engine = new TemplateEngine();

    public String generateUserNote(String profileType,
                                   String userName, String userDni, String userEmail,
                                   String motivo,
                                   List<AssetItem> assets, List<CountableItem> countables,
                                   String observations) throws IOException {
        String template = loadTemplate(resolveTemplateName(profileType));

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("TEMPLATE_NAME", toDisplayName(profileType));
        tokens.put("FECHA", LocalDate.now().format(DATE_FMT));
        tokens.put("NOMBRE", userName);
        tokens.put("DNI", userDni);
        tokens.put("EMAIL", userEmail);
        tokens.put("MOTIVO", motivo != null ? motivo : "");
        tokens.put("OBSERVACIONES", observations);

        return engine.render(template, tokens, "ITEMS", buildItemTokensFromLive(assets, countables));
    }

    public String generateProviderNote(String providerName, String cuit,
                                       String responsibleName, String responsibleDni,
                                       String motivo,
                                       List<AssetItem> assets, List<CountableItem> countables,
                                       String observations) throws IOException {
        String template = loadTemplate("proveedor.html");

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("TEMPLATE_NAME", "Entrega - Proveedor");
        tokens.put("FECHA", LocalDate.now().format(DATE_FMT));
        tokens.put("PROVEEDOR", providerName);
        tokens.put("CUIT", cuit);
        tokens.put("RESPONSABLE", responsibleName);
        tokens.put("RESPONSABLE_DNI", responsibleDni);
        tokens.put("MOTIVO", motivo != null ? motivo : "");
        tokens.put("OBSERVACIONES", observations);

        return engine.render(template, tokens, "ITEMS", buildItemTokensFromLive(assets, countables));
    }

    /** Re-renders a stored NoteReport back into HTML for the history detail view. */
    public String generateFromStoredReport(NoteReport report) throws IOException {
        String templateName = report.getProviderName() != null
            ? "proveedor.html"
            : resolveTemplateName(report.getProfileType());
        String template = loadTemplate(templateName);

        String dateStr = report.getCreatedAt() != null
            ? report.getCreatedAt().format(DT_FMT) : "";

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("TEMPLATE_NAME", toDisplayName(report.getProfileType()));
        tokens.put("FECHA", dateStr);
        tokens.put("NOMBRE",   orEmpty(report.getUserName()));
        tokens.put("DNI",      orEmpty(report.getUserDni()));
        tokens.put("EMAIL",    orEmpty(report.getUserEmail()));
        tokens.put("MOTIVO",   orEmpty(report.getMotivo()));
        tokens.put("OBSERVACIONES", "");
        tokens.put("PROVEEDOR",      orEmpty(report.getProviderName()));
        tokens.put("CUIT",           orEmpty(report.getCuit()));
        tokens.put("RESPONSABLE",    orEmpty(report.getUserName()));
        tokens.put("RESPONSABLE_DNI", orEmpty(report.getUserDni()));

        List<Map<String, String>> itemTokens = new ArrayList<>();
        if (report.getItems() != null) {
            for (NoteReportItem item : report.getItems()) {
                Map<String, String> t = new LinkedHashMap<>();
                t.put("TYPE",       orEmpty(item.getTypeName()));
                t.put("BRAND",      orEmpty(item.getBrandName()));
                t.put("MODEL",      orEmpty(item.getModelName()));
                if (item.isAsset()) {
                    t.put("SERIAL",      orEmpty(item.getSerialNumber()));
                    t.put("ACTIVOFIJO",  orEmpty(item.getAf()));
                } else {
                    int qty = item.getQuantity();
                    t.put("SERIAL",     qty > 1 ? "Cant: " + qty : "");
                    t.put("ACTIVOFIJO", "");
                }
                t.put("DETAILS", orEmpty(item.getObservations()));
                itemTokens.add(t);
            }
        }

        return engine.render(template, tokens, "ITEMS", itemTokens);
    }

    private List<Map<String, String>> buildItemTokensFromLive(List<AssetItem> assets,
                                                               List<CountableItem> countables) {
        List<Map<String, String>> result = new ArrayList<>();
        for (AssetItem a : assets) {
            Map<String, String> t = new LinkedHashMap<>();
            t.put("TYPE", a.getType().get());
            t.put("BRAND", a.getBrand().get());
            t.put("MODEL", a.getModel().get());
            t.put("SERIAL", a.getSerial().get());
            t.put("ACTIVOFIJO", a.getAf().get());
            t.put("DETAILS", a.getObservations().get());
            result.add(t);
        }
        for (CountableItem c : countables) {
            Map<String, String> t = new LinkedHashMap<>();
            t.put("TYPE", c.getType().get());
            t.put("BRAND", c.getBrand().get());
            t.put("MODEL", c.getModel().get());
            int qty = c.getQuantity().get();
            t.put("SERIAL", qty > 1 ? "Cant: " + qty : "");
            t.put("ACTIVOFIJO", "");
            t.put("DETAILS", c.getObservations().get());
            result.add(t);
        }
        return result;
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
            case "RECAMBIO"            -> "Recambio";
            case "ENTREGA - PROVEEDOR" -> "Entrega - Proveedor";
            default                    -> profileType;
        };
    }

    private String resolveTemplateName(String profileType) {
        if (profileType == null) return "entrega.html";
        return switch (profileType.toUpperCase()) {
            case "DEVOLUCIÓN"      -> "devolucion.html";
            case "FIN DE CONTRATO" -> "entrega - fin de contrato.html";
            default                -> "entrega.html";
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
