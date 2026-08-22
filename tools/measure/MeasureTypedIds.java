import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the rule behind typed prototype id references: a YAML key names a prototype when some
 * {@code [DataField]} of the checkout declares it {@code ProtoId<T>}, {@code EntProtoId} or through
 * a prototype id serializer. The rule is read off the shipped index, not restated here.
 *
 * <p>It is a rule by key name, so it is global where the truth is per owning type — the backend
 * knows the owner, an index of files does not — and being wrong costs differently for each of the
 * three things that read a reference. Ctrl+click lands nowhere, Find Usages shows a row too many,
 * and rename <em>writes</em>: a value that only looks like a reference is rewritten in the content.
 * So what has to be bounded is not the number of references gained but the number of values under
 * an accepted key that are no prototype id at all — every one of them is a key name whose meaning
 * depends on the owner, and an upper bound for what a rename could corrupt.
 */
public final class MeasureTypedIds {
    /** A key of a mapping, or an item of a block sequence, with the value written after it. */
    private static final Pattern ENTRY = Pattern.compile("^(\\s*)(?:-\\s+)?([A-Za-z_][\\w.]*)\\s*:\\s*(.*)$");

    private static final Pattern ITEM = Pattern.compile("^(\\s*)-\\s*(.*)$");

    /** Keys of the five names the plugin has always taken for references, kept apart in the report. */
    private static final Set<String> SPELLED_OUT = Set.of("parent", "proto", "prototype", "entity", "id");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object fields = MeasureReferences.companion("RobustDataFieldIndex");
        Method index = fields.getClass().getMethod("index", CharSequence.class);
        String prefix = (String) MeasureReferences.pluginClass("RobustDataFieldIndex")
            .getDeclaredField("PROTOTYPE_FIELD_KEY").get(null);

        String plainPrefix = (String) MeasureReferences.pluginClass("RobustDataFieldIndex")
            .getDeclaredField("PLAIN_FIELD_KEY").get(null);

        Map<String, Set<String>> named = new TreeMap<>();
        Set<String> otherwise = new java.util.TreeSet<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Map.Entry<String, String> entry : ((Map<String, String>) index.invoke(fields, text)).entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    named.computeIfAbsent(entry.getKey().substring(prefix.length()), k -> new java.util.TreeSet<>())
                        .add(entry.getValue());
                } else if (entry.getKey().startsWith(plainPrefix)) {
                    otherwise.add(entry.getKey().substring(plainPrefix.length()));
                }
            }
        }

        // The rule itself: a name is trusted only when nothing in the checkout declares it as
        // anything but a prototype id. Without this difference the accepted set holds `name`,
        // `values`, `tags` and `key`, and 85244 values under them are no ids at all.
        Set<String> ambiguous = new java.util.TreeSet<>(named.keySet());
        ambiguous.retainAll(otherwise);
        named.keySet().removeAll(otherwise);
        System.out.println("names declared as a prototype id somewhere: " + (named.size() + ambiguous.size())
            + ", of them also declared otherwise: " + ambiguous.size());
        System.out.println("rejected: " + ambiguous);

        Object ids = MeasureReferences.companion("RobustPrototypeIdIndex");
        Method prototypeIds = ids.getClass().getMethod("prototypeIds", CharSequence.class);

        List<Path> prototypes = MeasureHoles.prototypes(root);
        Set<String> declared = new HashSet<>();
        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text != null) declared.addAll(((Map<String, String>) prototypeIds.invoke(ids, text)).keySet());
        }

        Map<String, int[]> perKey = new HashMap<>();
        Map<String, List<String>> strangers = new TreeMap<>();
        int gained = 0;
        int spelledOut = 0;
        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Map.Entry<String, String> value : valuesUnderKeys(text, named.keySet())) {
                String key = value.getKey();
                String id = value.getValue();
                int[] counts = perKey.computeIfAbsent(key, k -> new int[2]);
                counts[0]++;
                if (declared.contains(id)) {
                    counts[1]++;
                    if (SPELLED_OUT.contains(key)) spelledOut++; else gained++;
                } else if (strangers.computeIfAbsent(key, k -> new ArrayList<>()).size() < 4) {
                    strangers.get(key).add(id + "   (" + root.relativize(file) + ")");
                }
            }
        }

        System.out.println("keys that name a prototype: " + named.size()
            + " (of them already taken for references: "
            + named.keySet().stream().filter(SPELLED_OUT::contains).count() + ")");
        System.out.println("values under them: " + perKey.values().stream().mapToInt(c -> c[0]).sum()
            + ", references gained: " + gained + ", already found by name: " + spelledOut);

        List<Map.Entry<String, int[]>> worst = new ArrayList<>(perKey.entrySet());
        worst.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[1] - e.getValue()[0]));
        System.out.println();
        System.out.println("keys whose values are not ids (an upper bound for what a rename could corrupt):");
        int strange = 0;
        for (Map.Entry<String, int[]> entry : worst) {
            int missing = entry.getValue()[0] - entry.getValue()[1];
            if (missing == 0) continue;
            strange += missing;
            System.out.println("   " + entry.getKey() + ": " + missing + " of " + entry.getValue()[0]
                + " -> " + strangers.getOrDefault(entry.getKey(), List.of()));
        }
        System.out.println("NOT AN ID: " + strange);

        // A name whose values are mostly not ids is a name that means something else where it is
        // used, and taking it for a reference would put a rename over values it must not touch.
        // A handful of strangers under a name that is otherwise all ids is the normal state:
        // `roles:` names components in one objective and prototypes everywhere else.
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : perKey.entrySet()) {
            int missing = entry.getValue()[0] - entry.getValue()[1];
            if (missing > entry.getValue()[1]) wrong.add(entry.getKey() + " (" + missing
                + " of " + entry.getValue()[0] + ")");
        }
        if (!wrong.isEmpty()) {
            System.out.println();
            System.out.println("Names taken for prototype ids whose values are mostly not ids: " + wrong);
            System.exit(1);
        }

        dictionaries(root, prototypes, declared, otherwise);
    }

    /**
     * The other half of the rule: a datafield declared {@code Dictionary<_, ProtoId>} writes a
     * mapping whose keys the author invents and whose values are ids — {@code mask:
     * ClothingMaskBreath} under {@code equipment:}. Nothing else in the plugin can reach that value:
     * the name above it is a datafield of nothing, and would be rejected as ambiguous even if it
     * were one. Bounded the same way, by the values under an accepted name that are no ids at all.
     */
    @SuppressWarnings("unchecked")
    private static void dictionaries(Path root, List<Path> prototypes, Set<String> declared, Set<String> otherwise)
        throws Exception {
        Object fields = MeasureReferences.companion("RobustDataFieldIndex");
        Method index = fields.getClass().getMethod("index", CharSequence.class);
        String prefix = (String) MeasureReferences.pluginClass("RobustDataFieldIndex")
            .getDeclaredField("PROTOTYPE_VALUE_FIELD_KEY").get(null);

        Set<String> named = new java.util.TreeSet<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (String key : ((Map<String, String>) index.invoke(fields, text)).keySet()) {
                if (key.startsWith(prefix)) named.add(key.substring(prefix.length()));
            }
        }
        int declaredNames = named.size();
        named.removeAll(otherwise);

        Map<String, int[]> perKey = new TreeMap<>();
        Map<String, List<String>> strangers = new TreeMap<>();
        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Map.Entry<String, String> value : valuesInMappings(text, named)) {
                int[] counts = perKey.computeIfAbsent(value.getKey(), k -> new int[2]);
                counts[0]++;
                if (declared.contains(value.getValue())) {
                    counts[1]++;
                } else if (strangers.computeIfAbsent(value.getKey(), k -> new ArrayList<>()).size() < 4) {
                    strangers.get(value.getKey()).add(value.getValue() + "   (" + root.relativize(file) + ")");
                }
            }
        }

        System.out.println();
        System.out.println("names holding ids in the values of a mapping: " + declaredNames
            + ", of them rejected as ambiguous: " + (declaredNames - named.size()));
        System.out.println("accepted: " + named);
        int total = perKey.values().stream().mapToInt(c -> c[0]).sum();
        int ids = perKey.values().stream().mapToInt(c -> c[1]).sum();
        System.out.println("values under them: " + total + ", references gained: " + ids);

        int strange = 0;
        for (Map.Entry<String, int[]> entry : perKey.entrySet()) {
            int missing = entry.getValue()[0] - entry.getValue()[1];
            if (missing == 0) continue;
            strange += missing;
            System.out.println("   " + entry.getKey() + ": " + missing + " of " + entry.getValue()[0]
                + " -> " + strangers.getOrDefault(entry.getKey(), List.of()));
        }
        System.out.println("NOT AN ID IN A MAPPING: " + strange);

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : perKey.entrySet()) {
            int missing = entry.getValue()[0] - entry.getValue()[1];
            if (missing > entry.getValue()[1]) wrong.add(entry.getKey() + " (" + missing
                + " of " + entry.getValue()[0] + ")");
        }
        if (!wrong.isEmpty()) {
            System.out.println();
            System.out.println("Dictionary names whose values are mostly not ids: " + wrong);
            System.exit(1);
        }
    }

    /**
     * Values written inside the mapping under any of [keys] — one per line, or as items of a list
     * where the value type is a collection. The inline form ({@code equipment: {mask: X}}) is not
     * read: the content writes none, and a brace on the key line ends the block here as it does for
     * the plugin's own walk over PSI.
     */
    private static List<Map.Entry<String, String>> valuesInMappings(String text, Set<String> keys) {
        List<Map.Entry<String, String>> found = new ArrayList<>();
        String open = null;
        int openIndent = 0;
        for (String raw : text.split("\n", -1)) {
            String line = strip(raw);
            if (line.isBlank()) continue;

            int indent = line.length() - line.stripLeading().length();
            if (open != null && indent <= openIndent) open = null;

            Matcher entry = ENTRY.matcher(line);
            if (open == null) {
                if (entry.matches() && keys.contains(entry.group(2)) && entry.group(3).trim().isEmpty()) {
                    open = entry.group(2);
                    openIndent = entry.group(1).length();
                }
                continue;
            }

            if (entry.matches()) {
                for (String item : items(entry.group(3).trim())) found.add(Map.entry(open, item));
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (item.matches() && looksLikeId(item.group(2).trim())) {
                found.add(Map.entry(open, unquote(item.group(2).trim())));
            }
        }
        return found;
    }

    /** Values written under any of [keys], flat: a scalar, an inline list or a block sequence. */
    private static List<Map.Entry<String, String>> valuesUnderKeys(String text, Set<String> keys) {
        List<Map.Entry<String, String>> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        String open = null;
        int openIndent = 0;
        for (String raw : lines) {
            String line = strip(raw);
            if (line.isBlank()) continue;

            Matcher entry = ENTRY.matcher(line);
            if (entry.matches()) {
                String key = entry.group(2);
                String value = entry.group(3).trim();
                open = null;
                if (!keys.contains(key)) continue;
                if (value.isEmpty()) {
                    open = key;
                    openIndent = entry.group(1).length();
                } else {
                    for (String item : items(value)) found.add(Map.entry(key, item));
                }
                continue;
            }

            Matcher item = ITEM.matcher(line);
            if (open != null && item.matches() && item.group(1).length() >= openIndent) {
                String value = item.group(2).trim();
                if (!value.isEmpty() && looksLikeId(value)) found.add(Map.entry(open, value));
            } else if (!item.matches()) {
                open = null;
            }
        }
        return found;
    }

    /** A flow sequence is several values on one line; anything else is one value. */
    private static List<String> items(String value) {
        List<String> items = new ArrayList<>();
        if (value.startsWith("[")) {
            for (String part : value.substring(1).replace("]", "").split(",")) {
                String item = part.trim();
                if (looksLikeId(item)) items.add(unquote(item));
            }
        } else if (looksLikeId(value)) {
            items.add(unquote(value));
        }
        return items;
    }

    private static boolean looksLikeId(String value) {
        String text = unquote(value);
        if (text.isEmpty() || text.startsWith("!") || text.startsWith("*") || text.startsWith("&")) return false;
        if (text.startsWith("{") || text.startsWith("[")) return false;
        // The same three words the validator refuses to read as an id — and the reason they matter
        // here is narrower: a rename rewrites values equal to the id being renamed, and `null` is
        // never one of them. Counting them as strangers would hide the names that really are wrong.
        if (text.equals("null") || text.equals("true") || text.equals("false")) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') return false;
        }
        return Character.isLetter(text.charAt(0));
    }

    private static String unquote(String value) {
        String text = value.trim();
        if (text.length() > 1 && (text.startsWith("\"") && text.endsWith("\"")
            || text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** Comments are not values, and a trailing one sits behind a space. */
    private static String strip(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') quoted = !quoted;
            if (c == '#' && !quoted && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line.replace("\r", "");
    }
}
