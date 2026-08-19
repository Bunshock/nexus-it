package com.bunshock.note_app_for_it_frontend.models.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public class SnValidation {
    private final int modelId;
    private final String regexPattern;
    private final boolean isActive;

    public SnValidation(int modelId, String regexPattern, boolean isActive) {
        this.modelId = modelId;
        this.regexPattern = regexPattern;
        this.isActive = isActive;
    }

    public int getModelId() { return modelId; }
    public String getRegexPattern() { return regexPattern; }
    public boolean isActive() { return isActive; }

    public OptionalInt deriveExpectedLength() {
        return deriveExpectedLength(regexPattern);
    }

    public record TemplateSegment(String text, boolean fixed) {}

    public List<TemplateSegment> templateParts() {
        return generateTemplateParts(regexPattern);
    }

    public static List<TemplateSegment> generateTemplateParts(String regex) {
        if (regex == null || regex.isEmpty()) return List.of();
        String r = regex;
        if (r.startsWith("^")) r = r.substring(1);
        if (r.endsWith("$")) r = r.substring(0, r.length() - 1);

        List<TemplateSegment> result = new ArrayList<>();
        int i = 0;
        while (i < r.length()) {
            String rep;
            boolean fixed;
            char c = r.charAt(i);

            if (c == '[') {
                int close = r.indexOf(']', i + 1);
                if (close == -1) return List.of(new TemplateSegment(regex, false));
                rep = representativeForClass(r.substring(i + 1, close));
                fixed = false;
                i = close + 1;
            } else if (c == '\\') {
                if (i + 1 >= r.length()) return List.of(new TemplateSegment(regex, false));
                rep = switch (r.charAt(i + 1)) {
                    case 'd'      -> "0";
                    case 'D'      -> "A";
                    case 'w', 'W' -> "X";
                    case 's'      -> " ";
                    default       -> String.valueOf(r.charAt(i + 1));
                };
                fixed = false;
                i += 2;
            } else if (c == '(' || c == ')' || c == '|') {
                return List.of(new TemplateSegment(regex, false));
            } else {
                rep = String.valueOf(c);
                fixed = true;
                i++;
            }

            int repeat = 1;
            boolean variable = false;
            if (i < r.length()) {
                char q = r.charAt(i);
                if (q == '{') {
                    int close = r.indexOf('}', i + 1);
                    if (close == -1) return List.of(new TemplateSegment(regex, false));
                    String qs = r.substring(i + 1, close);
                    if (qs.contains(",")) {
                        String[] parts = qs.split(",");
                        try { repeat = Integer.parseInt(parts[0].trim()); } catch (NumberFormatException e) { repeat = 1; }
                        variable = true;
                    } else {
                        try { repeat = Integer.parseInt(qs.trim()); } catch (NumberFormatException e) { repeat = 1; }
                    }
                    i = close + 1;
                } else if (q == '*') { repeat = 3; variable = true; i++;
                } else if (q == '+') { i++;
                } else if (q == '?') { i++;
                }
            }

            String segText = rep.repeat(repeat) + (variable ? "..." : "");
            if (!result.isEmpty() && result.get(result.size() - 1).fixed() == fixed) {
                TemplateSegment last = result.remove(result.size() - 1);
                result.add(new TemplateSegment(last.text() + segText, fixed));
            } else {
                result.add(new TemplateSegment(segText, fixed));
            }
        }
        return result;
    }

    public String formatHint() {
        return generateTemplate(regexPattern);
    }

    public static String generateTemplate(String regex) {
        if (regex == null || regex.isEmpty()) return "";
        String r = regex;
        if (r.startsWith("^")) r = r.substring(1);
        if (r.endsWith("$")) r = r.substring(0, r.length() - 1);

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < r.length()) {
            String rep;
            char c = r.charAt(i);

            if (c == '[') {
                int close = r.indexOf(']', i + 1);
                if (close == -1) return regex;
                rep = representativeForClass(r.substring(i + 1, close));
                i = close + 1;
            } else if (c == '\\') {
                if (i + 1 >= r.length()) return regex;
                rep = switch (r.charAt(i + 1)) {
                    case 'd'       -> "0";
                    case 'D'       -> "A";
                    case 'w', 'W'  -> "X";
                    case 's'       -> " ";
                    default        -> String.valueOf(r.charAt(i + 1));
                };
                i += 2;
            } else if (c == '(' || c == ')' || c == '|') {
                return regex;
            } else {
                rep = String.valueOf(c);
                i++;
            }

            int repeat = 1;
            boolean variable = false;
            if (i < r.length()) {
                char q = r.charAt(i);
                if (q == '{') {
                    int close = r.indexOf('}', i + 1);
                    if (close == -1) return regex;
                    String qs = r.substring(i + 1, close);
                    if (qs.contains(",")) {
                        String[] parts = qs.split(",");
                        try { repeat = Integer.parseInt(parts[0].trim()); } catch (NumberFormatException e) { repeat = 1; }
                        variable = true;
                    } else {
                        try { repeat = Integer.parseInt(qs.trim()); } catch (NumberFormatException e) { repeat = 1; }
                    }
                    i = close + 1;
                } else if (q == '*') { repeat = 3; variable = true; i++;
                } else if (q == '+') { i++;
                } else if (q == '?') { i++;
                }
            }

            result.append(rep.repeat(repeat));
            if (variable) result.append("...");
        }
        return result.toString();
    }

    private static String representativeForClass(String cls) {
        boolean hasUpper = cls.contains("A-Z");
        boolean hasLower = cls.contains("a-z");
        boolean hasDigit = cls.contains("0-9");
        if (hasUpper && hasDigit) return "X";
        if (hasUpper || (hasUpper && hasLower)) return "A";
        if (hasLower) return "a";
        if (hasDigit) return "0";
        if (!cls.isEmpty() && cls.charAt(0) != '^') return String.valueOf(cls.charAt(0));
        return "X";
    }

    public static OptionalInt deriveExpectedLength(String regex) {
        if (regex == null || regex.isEmpty()) return OptionalInt.empty();
        String r = regex;
        if (r.startsWith("^")) r = r.substring(1);
        if (r.endsWith("$")) r = r.substring(0, r.length() - 1);

        int total = 0;
        int i = 0;
        while (i < r.length()) {
            char c = r.charAt(i);
            if (c == '[') {
                int close = r.indexOf(']', i + 1);
                if (close == -1) return OptionalInt.empty();
                i = close + 1;
            } else if (c == '\\') {
                if (i + 1 >= r.length()) return OptionalInt.empty();
                i += 2;
            } else if (c == '(' || c == ')' || c == '|') {
                return OptionalInt.empty();
            } else {
                i++;
            }

            if (i < r.length() && r.charAt(i) == '{') {
                int close = r.indexOf('}', i + 1);
                if (close == -1) return OptionalInt.empty();
                String q = r.substring(i + 1, close);
                if (q.contains(",")) return OptionalInt.empty();
                try {
                    total += Integer.parseInt(q.trim());
                } catch (NumberFormatException e) {
                    return OptionalInt.empty();
                }
                i = close + 1;
            } else if (i < r.length() && "*+?".indexOf(r.charAt(i)) >= 0) {
                return OptionalInt.empty();
            } else {
                total += 1;
            }
        }
        return OptionalInt.of(total);
    }
}
