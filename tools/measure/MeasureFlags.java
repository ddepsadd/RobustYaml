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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the flag values: the map from a serializer tag to the enum that spells its members, and the
 * reading of the values written under those fields.
 *
 * <p>The map is the engine's own: {@code SerializationManager} walks every {@code [Flags]} enum and
 * registers it under each {@code [FlagsFor(typeof(tag))]} it carries, refusing a second enum for the
 * same tag. That is what the backend reproduces with a reverse search, and what the first half of
 * this measurement checks — a tag with two enums means the checkout would not even load.
 *
 * <p>The second half is the reading rule, called through {@code RobustValidation.unknownMembers} so
 * the shipped code is measured and not a model of it. It is deliberately fed only the key names that
 * mean flags <em>everywhere</em> in the checkout, the same filter {@code MeasureScalars} uses: the
 * backend knows the type per owner, a measurement cannot. The names it has to drop are the whole
 * reason the plugin does not ship a by-name rule for flags, so they are counted rather than hidden —
 * under {@code mask:} the content writes light-mask paths and clothing ids, under {@code layer:} the
 * humanoid sprite layers.
 */
public final class MeasureFlags {
    private static final String ATTRIBUTE = "DataField";

    private static final Pattern SERIALIZER = Pattern.compile("\\bFlagSerializer\\s*<\\s*([\\w.]+)");

    private static final Pattern TAG = Pattern.compile("(?s)^(?:Attribute)?\\s*\\(\\s*\"([^\"]+)\"");

    /** An enum declaration with at least one modifier before it, the way the index finds classes. */
    private static final Pattern ENUM = Pattern.compile("\\b(?:public|internal|private|protected)[\\w\\s]*?\\benum\\s+(\\w+)");

    private static final Pattern FLAGS_FOR = Pattern.compile("\\bFlagsFor\\s*\\(\\s*typeof\\s*\\(\\s*([\\w.]+)");

    private static final Pattern MEMBER = Pattern.compile("(?m)^\\s*([A-Za-z_]\\w*)\\s*(?==|,|$)");

    private static final Pattern KEY_VALUE = Pattern.compile("(?m)^([ \\t]*)(?:-[ \\t]+)?(\\w+)[ \\t]*:[ \\t]*(.*?)[ \\t]*\\r?$");

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Class<?> validation = MeasureReferences.pluginClass("RobustValidation");
        Object instance = validation.getField("INSTANCE").get(null);
        Method unknownMembers = validation.getMethod(
            "unknownMembers", List.class, String.class, boolean.class);

        Map<String, Set<String>> enumsOfTag = new TreeMap<>();
        Map<String, List<String>> membersOfEnum = new TreeMap<>();
        Map<String, Set<String>> tagsOfKey = new TreeMap<>();
        Set<String> otherwise = new TreeSet<>();

        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            String blanked = Cs.blankCommentsAndLiterals(text);

            collectFlagEnums(blanked, enumsOfTag, membersOfEnum);
            collectFields(text, blanked, tagsOfKey, otherwise);
        }

        System.out.println("flag enums: " + membersOfEnum.size() + ", tags they answer for: "
            + enumsOfTag.size());
        for (Map.Entry<String, Set<String>> entry : enumsOfTag.entrySet()) {
            String enumeration = entry.getValue().iterator().next();
            System.out.println("   " + entry.getKey() + " -> " + enumeration
                + " (" + membersOfEnum.getOrDefault(enumeration, List.of()).size() + " members)"
                + (entry.getValue().size() > 1 ? "   AMBIGUOUS: " + entry.getValue() : ""));
        }

        // A name is only read as flags when nothing else in the checkout declares it. `layer` and
        // `mask` never survive this, and that is the finding: their values are checked by the
        // backend, per owner, or not at all.
        Map<String, String> byName = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : tagsOfKey.entrySet()) {
            if (entry.getValue().size() == 1 && !otherwise.contains(entry.getKey())) {
                byName.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }
        System.out.println("keys carrying a FlagSerializer: " + tagsOfKey.keySet()
            + ", of them unambiguous: " + byName.keySet());

        Map<String, int[]> counts = new TreeMap<>();
        Map<String, List<String>> rejected = new TreeMap<>();
        Map<String, int[]> ambiguous = new TreeMap<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Value value : values(text)) {
                Set<String> tags = tagsOfKey.get(value.key);
                if (tags == null) continue;

                String tag = byName.get(value.key);
                boolean checked = tag != null;
                String enumeration = checked
                    ? enumsOfTag.get(tag).iterator().next()
                    : enumsOfTag.get(tags.iterator().next()).iterator().next();
                List<String> members = membersOfEnum.getOrDefault(enumeration, List.of());

                @SuppressWarnings("unchecked")
                List<String> unknown = (List<String>) unknownMembers.invoke(
                    instance, members, value.text, true);

                Map<String, int[]> into = checked ? counts : ambiguous;
                int[] count = into.computeIfAbsent(value.key, k -> new int[2]);
                count[0]++;
                if (unknown.isEmpty()) continue;

                count[1]++;
                if (!checked) continue;
                List<String> shown = rejected.computeIfAbsent(value.key, k -> new ArrayList<>());
                if (shown.size() < 5) {
                    shown.add(value.text + "   (" + root.relativize(file) + ")");
                }
            }
        }

        System.out.println();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            System.out.println("checked " + entry.getKey() + ": " + entry.getValue()[0]
                + " values, rejected " + entry.getValue()[1]
                + " " + rejected.getOrDefault(entry.getKey(), List.of()));
        }
        int wrongByName = 0;
        for (Map.Entry<String, int[]> entry : ambiguous.entrySet()) {
            wrongByName += entry.getValue()[1];
            System.out.println("not checked by name " + entry.getKey() + ": " + entry.getValue()[0]
                + " values, of them no flag at all " + entry.getValue()[1]);
        }
        System.out.println("VALUES A BY-NAME RULE WOULD PAINT RED: " + wrongByName);

        int rejects = counts.values().stream().mapToInt(c -> c[1]).sum();
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : enumsOfTag.entrySet()) {
            if (entry.getValue().size() > 1) broken.add(entry.getKey());
        }
        if (!broken.isEmpty() || rejects != 0) {
            System.out.println();
            System.out.println(broken.isEmpty()
                ? "A value under an unambiguous flag key does not name a member of its enum."
                : "Tags answered by more than one enum: " + broken);
            System.exit(1);
        }
    }

    /** Enums marked {@code [FlagsFor(typeof(tag))]}, with their members. */
    private static void collectFlagEnums(
        String blanked, Map<String, Set<String>> enumsOfTag, Map<String, List<String>> members) {
        Matcher declaration = ENUM.matcher(blanked);
        while (declaration.find()) {
            String attributes = Cs.attributesBefore(blanked, declaration.start());
            Matcher tag = FLAGS_FOR.matcher(attributes);
            if (!tag.find()) continue;

            String name = declaration.group(1);
            members.computeIfAbsent(name, k -> membersOf(blanked, declaration.end()));
            do {
                enumsOfTag.computeIfAbsent(shortName(tag.group(1)), k -> new TreeSet<>()).add(name);
            } while (tag.find());
        }
    }

    /** Member names of the enum whose body starts after {@code from}. */
    private static List<String> membersOf(String blanked, int from) {
        int open = blanked.indexOf('{', from);
        if (open < 0) return List.of();

        int depth = 0;
        int i = open;
        while (i < blanked.length()) {
            char c = blanked.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) break;
        }

        List<String> members = new ArrayList<>();
        Matcher member = MEMBER.matcher(blanked.substring(open + 1, i - 1));
        while (member.find()) members.add(member.group(1));
        return members;
    }

    /** YAML keys of {@code [DataField]}s serialized with a FlagSerializer, and of all the others. */
    private static void collectFields(
        String text, String blanked, Map<String, Set<String>> tagsOfKey, Set<String> otherwise) {
        int at = blanked.indexOf(ATTRIBUTE);
        while (at >= 0) {
            int declaration = MeasureScalars.declarationAfter(blanked, at + ATTRIBUTE.length());
            int arguments = MeasureScalars.argumentsEnd(blanked, at + ATTRIBUTE.length());
            if (declaration > 0) {
                String[] member = MeasureScalars.member(blanked, declaration);
                if (member != null) {
                    Matcher tag = TAG.matcher(text.substring(at + ATTRIBUTE.length(), declaration));
                    String key = tag.find() ? tag.group(1) : MeasureScalars.camelCase(member[1]);
                    Matcher serializer = SERIALIZER.matcher(blanked.substring(at, arguments));
                    if (serializer.find()) {
                        tagsOfKey.computeIfAbsent(key, k -> new TreeSet<>())
                            .add(shortName(serializer.group(1)));
                    } else {
                        otherwise.add(key);
                    }
                }
            }
            at = blanked.indexOf(ATTRIBUTE, at + ATTRIBUTE.length());
        }
    }

    private static String shortName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static final class Value {
        private final String key;
        private final String text;

        private Value(String key, String text) {
            this.key = key;
            this.text = text;
        }
    }

    /**
     * Values written under a key, flat. A block sequence belongs to its key while the lines are
     * indented no less than it — <em>not</em> deeper, which is the whole point of
     * {@code RobustSequenceIndentHandler}: SS14 writes items at the level of their key, and out of
     * the 17 declarations of {@code airBlockedDirection} in the checkout only three are indented the
     * way the platform would have written them. Requiring a deeper indent found those three and
     * called it the whole content. The sibling that must still end the walk — the next
     * {@code - type:} of {@code components:} — sits one level shallower, at the indent of the key
     * that owns the list.
     */
    private static List<Value> values(String text) {
        List<Value> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        Matcher matcher = KEY_VALUE.matcher("");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace("\r", "");
            if (!matcher.reset(line).matches()) continue;

            String key = matcher.group(2);
            String value = MeasureScalars.value(matcher.group(3).isEmpty() ? " " : matcher.group(3));
            if (!value.isEmpty()) {
                for (String item : items(value)) found.add(new Value(key, item));
                continue;
            }

            int indent = matcher.group(1).length();
            for (int j = i + 1; j < lines.length; j++) {
                String item = lines[j].replace("\r", "");
                if (item.isBlank()) continue;
                int at = item.length() - item.stripLeading().length();
                if (at < indent || !item.stripLeading().startsWith("- ")) break;
                found.add(new Value(key, MeasureScalars.value(item.stripLeading().substring(2).trim())));
            }
        }
        return found;
    }

    /** A flow sequence is several values on one line; anything else is one value. */
    private static List<String> items(String value) {
        if (!value.startsWith("[") || !value.endsWith("]")) return List.of(value);

        List<String> items = new ArrayList<>();
        for (String part : value.substring(1, value.length() - 1).split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) items.add(item);
        }
        return items;
    }

    private MeasureFlags() {
    }
}
