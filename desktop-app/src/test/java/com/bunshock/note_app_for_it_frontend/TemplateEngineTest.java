package com.bunshock.note_app_for_it_frontend;

import java.util.List;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.services.TemplateEngine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TemplateEngineTest {

    private final TemplateEngine engine = new TemplateEngine();

    @Test
    void replacesSimpleTokens() {
        String template = "Hola {{NOMBRE}}, DNI: {{DNI}}";
        String result = engine.render(template, Map.of("NOMBRE", "Juan", "DNI", "12345678"), "ITEMS", List.of());
        assertEquals("Hola Juan, DNI: 12345678", result);
    }

    @Test
    void leavesUnknownTokensEmpty() {
        String result = engine.render("{{UNKNOWN}}", Map.of(), "ITEMS", List.of());
        assertEquals("", result);
    }

    @Test
    void expandsItemLoop() {
        String template = "{{#ITEMS}}<p>{{TYPE}}</p>{{/ITEMS}}";
        List<Map<String, String>> items = List.of(
            Map.of("TYPE", "Notebook"),
            Map.of("TYPE", "Monitor")
        );
        String result = engine.render(template, Map.of(), "ITEMS", items);
        assertEquals("<p>Notebook</p><p>Monitor</p>", result);
    }

    @Test
    void emptyLoopProducesNoOutput() {
        String template = "Before{{#ITEMS}}<p>{{TYPE}}</p>{{/ITEMS}}After";
        String result = engine.render(template, Map.of(), "ITEMS", List.of());
        assertEquals("BeforeAfter", result);
    }

    @Test
    void outerTokensAndLoopCombine() {
        String template = "{{NOMBRE}}{{#ITEMS}}-{{TYPE}}{{/ITEMS}}";
        String result = engine.render(template,
            Map.of("NOMBRE", "Test"),
            "ITEMS",
            List.of(Map.of("TYPE", "A"), Map.of("TYPE", "B")));
        assertEquals("Test-A-B", result);
    }
}
