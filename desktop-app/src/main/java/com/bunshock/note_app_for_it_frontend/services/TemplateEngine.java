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
     * Renders a template string by replacing {{TOKEN}} placeholders and
     * expanding {{#LOOP}}...{{/LOOP}} blocks for each item in the provided list.
     *
     * @param template   raw HTML template content
     * @param tokens     map of token name → replacement value
     * @param loopKey    the loop block identifier (e.g. "ITEMS")
     * @param loopItems  list of per-item token maps
     */
    public String render(String template, Map<String, String> tokens,
                         String loopKey, List<Map<String, String>> loopItems) {
        String result = expandLoop(template, loopKey, loopItems);
        result = replaceTokens(result, tokens);
        return result;
    }

    private String expandLoop(String template, String loopKey, List<Map<String, String>> items) {
        Matcher m = LOOP_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            if (!m.group(1).equals(loopKey)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String block = m.group(2);
            StringBuilder expanded = new StringBuilder();
            for (Map<String, String> itemTokens : items) {
                expanded.append(replaceTokens(block, itemTokens));
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
