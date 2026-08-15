import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the value validation: how many prototype values are checked, and how many the plugin would
 * reject.
 *
 * <p>The rules live in {@code RobustValidation.accepts} and are called by reflection, so this
 * measures the shipped code and not a model of it. A key name is only checked when every
 * {@code [DataField]} with that name in the whole checkout declares the same type — the plugin gets
 * the type from the backend per owner, the measurement cannot, and an ambiguous name (see
 * {@code state}: bool, string and a dozen enums) would produce noise that says nothing about the
 * rules.
 */
public final class MeasureScalars {
    private static final String ATTRIBUTE = "DataField";

    /** A customTypeSerializer replaces the reading rules, so the declared type says nothing. */
    private static final String TYPEOF = "typeof";

    private static final Pattern TAG = Pattern.compile("(?s)^(?:Attribute)?\\s*\\(\\s*\"([^\"]+)\"");

    private static final Pattern KEY_VALUE = Pattern.compile("(?m)^[ \\t]*(\\w+)[ \\t]*:[ \\t]*(\\S.*?)[ \\t]*\\r?$");

    /**
     * A flow sequence is a list of scalars, and the plugin sees it that way too: PSI hands the
     * annotator every item of {@code [ True, True ]} separately. Checking the brackets as one string
     * would measure the parser of this tool, not the rules.
     */
    private static List<String> items(String value) {
        if (!value.startsWith("[") || !value.endsWith("]")) return List.of(value);

        List<String> items = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        boolean quoted = false;
        for (char c : value.substring(1, value.length() - 1).toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                items.add(item.toString().trim());
                item.setLength(0);
            } else {
                item.append(c);
            }
        }
        items.add(item.toString().trim());
        return items;
    }

    private static final Set<String> MODIFIERS = Set.of(
        "public", "private", "protected", "internal", "static", "readonly", "required", "new",
        "override", "virtual", "sealed", "abstract", "partial", "const", "volatile", "extern");

    private static final Set<String> SKIPPED = Set.of("null", "");

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Class<?> validation = MeasureReferences.pluginClass("RobustValidation");
        Object instance = validation.getField("INSTANCE").get(null);
        Method accepts = validation.getMethod("accepts", String.class, String.class);

        Map<String, Set<String>> typesOfKey = new HashMap<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null || !text.contains(ATTRIBUTE)) continue;
            for (Map.Entry<String, String> field : datafields(text).entrySet()) {
                typesOfKey.computeIfAbsent(field.getKey(), k -> new HashSet<>()).add(field.getValue());
            }
        }

        Map<String, String> checkable = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : typesOfKey.entrySet()) {
            if (entry.getValue().size() != 1) continue;
            String type = entry.getValue().iterator().next();
            if (accepts.invoke(instance, type, "1") == null) continue;
            checkable.put(entry.getKey(), type);
        }

        Map<String, int[]> counts = new TreeMap<>();
        Map<String, List<String>> examples = new TreeMap<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            Matcher m = KEY_VALUE.matcher(text);
            while (m.find()) {
                String type = checkable.get(m.group(1));
                if (type == null) continue;

                String value = value(m.group(2));
                if (SKIPPED.contains(value) || "!*&".indexOf(value.charAt(0)) >= 0) continue;

                for (String item : items(value)) {
                    if (SKIPPED.contains(item) || item.isEmpty()) continue;

                    int[] count = counts.computeIfAbsent(type, k -> new int[2]);
                    count[0]++;
                    if (Boolean.FALSE.equals(accepts.invoke(instance, type, item))) {
                        count[1]++;
                        List<String> shown = examples.computeIfAbsent(type, k -> new ArrayList<>());
                        if (shown.size() < 5) {
                            shown.add(m.group(1) + ": " + value + "   (" + root.relativize(file) + ")");
                        }
                    }
                }
            }
        }

        System.out.println("datafield names with a single declared type: " + checkable.size()
            + " of " + typesOfKey.size());
        int values = counts.values().stream().mapToInt(c -> c[0]).sum();
        int bad = counts.values().stream().mapToInt(c -> c[1]).sum();
        System.out.println("values checked: " + values + ", rejected: " + bad);
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            System.out.println("   " + entry.getKey() + ": " + entry.getValue()[0]
                + " values, " + entry.getValue()[1] + " rejected");
            for (String example : examples.getOrDefault(entry.getKey(), List.of())) {
                System.out.println("      " + example);
            }
        }
    }

    /** Value text of a {@code key: value} line, with the trailing comment cut off outside quotes. */
    static String value(String tail) {
        char quote = tail.charAt(0);
        if (quote == '"' || quote == '\'') {
            int end = tail.indexOf(quote, 1);
            return end < 0 ? tail.substring(1) : tail.substring(1, end);
        }
        int comment = tail.indexOf(" #");
        return (comment < 0 ? tail : tail.substring(0, comment)).trim();
    }

    /** YAML key to declared type, for every {@code [DataField]} in one source file. */
    static Map<String, String> datafields(String text) {
        String blanked = Cs.blankCommentsAndLiterals(text);
        Map<String, String> result = new HashMap<>();
        int at = blanked.indexOf(ATTRIBUTE);
        while (at >= 0) {
            int declaration = declarationAfter(blanked, at + ATTRIBUTE.length());
            int arguments = argumentsEnd(blanked, at + ATTRIBUTE.length());
            if (declaration > 0) {
                String[] member = member(blanked, declaration);
                if (member != null) {
                    Matcher tag = TAG.matcher(text.substring(at + ATTRIBUTE.length(), declaration));
                    String key = tag.find() ? tag.group(1) : camelCase(member[1]);
                    boolean custom = blanked.substring(at, arguments).contains(TYPEOF);
                    result.put(key, custom ? TYPEOF : member[0]);
                }
            }
            at = blanked.indexOf(ATTRIBUTE, at + ATTRIBUTE.length());
        }
        return result;
    }

    /**
     * Offset just past the argument list of the attribute at {@code from}, so that a
     * {@code typeof(...)} in a neighbouring attribute is not mistaken for a custom serializer.
     */
    static int argumentsEnd(String blanked, int from) {
        int i = from;
        while (i < blanked.length() && Character.isWhitespace(blanked.charAt(i))) i++;
        if (i >= blanked.length() || blanked.charAt(i) != '(') return i;

        int depth = 0;
        while (i < blanked.length()) {
            char c = blanked.charAt(i++);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) break;
        }
        return i;
    }

    /** Offset just past the attribute lists an attribute at {@code from} belongs to. */
    static int declarationAfter(String blanked, int from) {
        int i = from;
        int depth = 1;
        while (i < blanked.length() && depth > 0) {
            char c = blanked.charAt(i++);
            if (c == '[') depth++;
            else if (c == ']') depth--;
        }
        if (depth > 0) return -1;
        while (true) {
            while (i < blanked.length() && Character.isWhitespace(blanked.charAt(i))) i++;
            if (i >= blanked.length() || blanked.charAt(i) != '[') return i;
            depth = 1;
            i++;
            while (i < blanked.length() && depth > 0) {
                char c = blanked.charAt(i++);
                if (c == '[') depth++;
                else if (c == ']') depth--;
            }
        }
    }

    /** {type, name} of the member declared at {@code start}, or null when it is not a field. */
    static String[] member(String blanked, int start) {
        int end = blanked.length();
        for (char stop : new char[] {';', '=', '{'}) {
            int at = blanked.indexOf(stop, start);
            if (at >= 0 && at < end) end = at;
        }
        if (end - start > 300) return null;

        List<String> tokens = new ArrayList<>();
        for (String token : blanked.substring(start, end).trim().split("\\s+")) {
            if (!token.isEmpty() && !MODIFIERS.contains(token)) tokens.add(token);
        }
        if (tokens.size() < 2) return null;

        String name = tokens.remove(tokens.size() - 1);
        if (!name.matches("\\w+")) return null;
        return new String[] {String.join("", tokens), name};
    }

    static String camelCase(String name) {
        String trimmed = name.replaceAll("^_+", "");
        if (trimmed.isEmpty()) return name;
        return Character.toLowerCase(trimmed.charAt(0)) + trimmed.substring(1);
    }
}
