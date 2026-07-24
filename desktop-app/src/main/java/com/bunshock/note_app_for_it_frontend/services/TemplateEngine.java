package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateEngine {

    private static final Pattern LOOP_PATTERN =
        Pattern.compile("\\{\\{#(\\w+)\\}\\}(.*?)\\{\\{/(\\1)\\}\\}", Pattern.DOTALL);
    private static final Pattern TOKEN_PATTERN =
        Pattern.compile("\\{\\{([\\w]+)\\}\\}");

    /**
     * Renders a template string by replacing {{TOKEN}} placeholders and expanding every
     * {{#LOOP}}...{{/LOOP}} block found. Each loop key is looked up in {@code loops}; a key
     * with no entry (or an empty list) expands to nothing, which doubles as a conditional block.
     * Loop blocks may nest a different key inside them (e.g. a one-entry-or-empty "existence"
     * loop wrapping a real per-item loop, so a whole section — header included — can disappear
     * when its item list is empty) — see {@link #expandLoops}.
     *
     * @param template raw HTML template content
     * @param tokens   map of token name → replacement value
     * @param loops    map of loop key → list of per-item token maps
     */
    public String render(String template, Map<String, String> tokens,
                         Map<String, List<Map<String, String>>> loops) {
        String result = expandLoops(template, loops);
        result = replaceTokens(result, tokens);
        return result;
    }

    // Recurses into each matched block before replacing its own-scope tokens, so a nested
    // {{#OTHER_KEY}}...{{/OTHER_KEY}} loop inside this block is resolved against the same full
    // loops map first. Existing single-level usages (no nested loop tags in their block) are
    // unaffected — the recursive call simply finds no matches and returns the block unchanged.
    private String expandLoops(String template, Map<String, List<Map<String, String>>> loops) {
        Matcher m = LOOP_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String block = m.group(2);
            List<Map<String, String>> items = loops.getOrDefault(m.group(1), List.of());
            StringBuilder expanded = new StringBuilder();
            for (Map<String, String> itemTokens : items) {
                String nested = expandLoops(block, loops);
                expanded.append(replaceTokens(nested, itemTokens));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(expanded.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String replaceTokens(String text, Map<String, String> tokens) {
        Matcher m = TOKEN_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value = tokens.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
