package com.bunshock.note_app_for_it_frontend.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;

public class NoteGenerationService {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TemplateEngine engine = new TemplateEngine();

    public String generateUserNote(String profileType,
                                   String userName, String userDni, String userEmail,
                                   String motivo,
                                   List<AssetItem> assets, List<CountableItem> countables,
                                   String observations) throws IOException {
        String templateName = resolveTemplateName(profileType);
        String template = loadTemplate(templateName);

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("TEMPLATE_NAME", profileType);
        tokens.put("FECHA", LocalDate.now().format(DATE_FMT));
        tokens.put("NOMBRE", userName);
        tokens.put("DNI", userDni);
        tokens.put("EMAIL", userEmail);
        tokens.put("MOTIVO", motivo != null ? motivo : "");
        tokens.put("OBSERVACIONES", observations);

        List<Map<String, String>> itemTokens = buildItemTokens(assets, countables);

        return engine.render(template, tokens, "ITEMS", itemTokens);
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

        List<Map<String, String>> itemTokens = buildItemTokens(assets, countables);

        return engine.render(template, tokens, "ITEMS", itemTokens);
    }

    private List<Map<String, String>> buildItemTokens(List<AssetItem> assets,
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

    private String resolveTemplateName(String profileType) {
        return switch (profileType.toUpperCase()) {
            case "DEVOLUCIÓN" -> "devolucion.html";
            case "FIN DE CONTRATO" -> "fin_contrato.html";
            case "RECAMBIO" -> "entrega.html";
            default -> "entrega.html";
        };
    }

    private String loadTemplate(String name) throws IOException {
        String path = "/com/bunshock/note_app_for_it_frontend/templates/" + name;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Template not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
