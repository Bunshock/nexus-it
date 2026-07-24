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
        String result = engine.render(template, Map.of("NOMBRE", "Juan", "DNI", "12345678"), Map.of());
        assertEquals("Hola Juan, DNI: 12345678", result);
    }

    @Test
    void leavesUnknownTokensEmpty() {
        String result = engine.render("{{UNKNOWN}}", Map.of(), Map.of());
        assertEquals("", result);
    }

    @Test
    void expandsItemLoop() {
        String template = "{{#ITEMS}}<p>{{TYPE}}</p>{{/ITEMS}}";
        List<Map<String, String>> items = List.of(
            Map.of("TYPE", "Notebook"),
            Map.of("TYPE", "Monitor")
        );
        String result = engine.render(template, Map.of(), Map.of("ITEMS", items));
        assertEquals("<p>Notebook</p><p>Monitor</p>", result);
    }

    @Test
    void emptyLoopProducesNoOutput() {
        String template = "Before{{#ITEMS}}<p>{{TYPE}}</p>{{/ITEMS}}After";
        String result = engine.render(template, Map.of(), Map.of("ITEMS", List.of()));
        assertEquals("BeforeAfter", result);
    }

    @Test
    void loopWithNoMatchingEntryProducesNoOutput() {
        String template = "Before{{#ITEMS}}<p>{{TYPE}}</p>{{/ITEMS}}After";
        String result = engine.render(template, Map.of(), Map.of());
        assertEquals("BeforeAfter", result);
    }

    @Test
    void outerTokensAndLoopCombine() {
        String template = "{{NOMBRE}}{{#ITEMS}}-{{TYPE}}{{/ITEMS}}";
        String result = engine.render(template,
            Map.of("NOMBRE", "Test"),
            Map.of("ITEMS", List.of(Map.of("TYPE", "A"), Map.of("TYPE", "B"))));
        assertEquals("Test-A-B", result);
    }

    @Test
    void multipleIndependentLoopsExpandSeparately() {
        String template = "{{#ITEMS}}<i>{{TYPE}}</i>{{/ITEMS}}{{#FAILURE}}<f>{{FAILURE_CAUSE}}</f>{{/FAILURE}}";
        String result = engine.render(template, Map.of(), Map.of(
            "ITEMS", List.of(Map.of("TYPE", "Notebook")),
            "FAILURE", List.of(Map.of("FAILURE_CAUSE", "No enciende"))));
        assertEquals("<i>Notebook</i><f>No enciende</f>", result);
    }

    @Test
    void conditionalBlockHiddenWhenLoopOmitted() {
        String template = "Before{{#FAILURE}}<p>{{FAILURE_CAUSE}}</p>{{/FAILURE}}After";
        String result = engine.render(template, Map.of(),
            Map.of("ITEMS", List.of(Map.of("TYPE", "Notebook"))));
        assertEquals("BeforeAfter", result);
    }

    // A different, named loop key nested inside another loop's block — used by the note item
    // tables, where a one-entry-or-empty "HAS_X" loop wraps a table (header included) and a
    // separate per-item "X" loop repeats just the rows, so the whole table can vanish when
    // the item list is empty instead of only hiding individual rows.
    @Test
    void nestedLoopInsideOuterBlockExpandsAgainstTheSameLoopsMap() {
        String template = "{{#HAS_ITEMS}}<table>{{#ITEMS}}<tr>{{TYPE}}</tr>{{/ITEMS}}</table>{{/HAS_ITEMS}}";
        String result = engine.render(template, Map.of(), Map.of(
            "HAS_ITEMS", List.of(Map.of()),
            "ITEMS", List.of(Map.of("TYPE", "Notebook"), Map.of("TYPE", "Monitor"))));
        assertEquals("<table><tr>Notebook</tr><tr>Monitor</tr></table>", result);
    }

    @Test
    void nestedLoopWrapperHidesWholeBlockWhenOuterLoopIsEmpty() {
        String template = "Before{{#HAS_ITEMS}}<table>{{#ITEMS}}<tr>{{TYPE}}</tr>{{/ITEMS}}</table>{{/HAS_ITEMS}}After";
        String result = engine.render(template, Map.of(), Map.of(
            "HAS_ITEMS", List.of(),
            "ITEMS", List.of(Map.of("TYPE", "Notebook"))));
        assertEquals("BeforeAfter", result);
    }
}
