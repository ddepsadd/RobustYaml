import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feasibility of warning about unused localization messages.
 *
 * <p>A key is used from four different places, and a check that knows only about prototypes would
 * light up most of the file: YAML values, string literals in C#, references from other Fluent
 * messages, and the engine itself — {@code LocalizationManager.Entity} builds `ent-&lt;prototypeId&gt;`
 * for every entity without the key ever being written down. This counts each source separately so
 * the size of every blind spot is known before an inspection is shipped.
 */
public final class MeasureDeadLocale {
    /**
     * A key assembled at runtime leaves only its head in the source: `$"accent-{name}-replacement"`
     * or `"cmd-" + command`. Everything starting with such a head has to count as used.
     */
    private static final Pattern PREFIX = Pattern.compile("\"([A-Za-z][\\w-]*-)(?:\\{|\"\\s*\\+)");

    /** `localizedDataset` names its messages by a prefix and a count, never one by one. */
    private static final Pattern DATASET_PREFIX =
        Pattern.compile("(?m)^\\s*prefix:\\s*\"?([A-Za-z][\\w-]*-)\"?\\s*$");

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object locale = companion("RobustLocaleIndex");
        Method messages = locale.getClass().getMethod("messages", CharSequence.class);

        Object values = companion("RobustYamlValueIndex");
        Method yamlValues = values.getClass().getMethod("values", CharSequence.class);

        // The same reader the rename uses, so the inspection cannot call a key dead that the rename
        // would have rewritten.
        Object usages = companion("RobustLocaleUsageIndex");
        Method literals = usages.getClass().getMethod("literals", CharSequence.class);
        Method placeables = usages.getClass().getMethod("placeables", CharSequence.class);

        Set<String> declared = new HashSet<>();
        Set<String> fluentReferences = new HashSet<>();
        for (Path file : MeasureHoles.sources(root.resolve("Resources").resolve("Locale"), ".ftl")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            @SuppressWarnings("unchecked")
            Map<String, ?> keys = (Map<String, ?>) messages.invoke(locale, text);
            declared.addAll(keys.keySet());

            for (Object usage : (List<?>) placeables.invoke(usages, text)) {
                fluentReferences.add(id(usage));
            }
        }

        Set<String> fromYaml = new HashSet<>();
        Set<String> datasetPrefixes = new HashSet<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            @SuppressWarnings("unchecked")
            Map<String, ?> keys = (Map<String, ?>) yamlValues.invoke(values, text);
            fromYaml.addAll(keys.keySet());

            Matcher d = DATASET_PREFIX.matcher(text);
            while (d.find()) datasetPrefixes.add(d.group(1));
        }

        Set<String> fromCode = new HashSet<>();
        Set<String> codePrefixes = new HashSet<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            for (Object usage : (List<?>) literals.invoke(usages, text)) fromCode.add(id(usage));

            Matcher p = PREFIX.matcher(text);
            while (p.find()) codePrefixes.add(p.group(1));
        }

        // `ent-<id>` and `ent-<id>.desc` are produced by the engine for every entity prototype.
        Set<String> entityKeys = new HashSet<>();
        for (String key : declared) {
            if (key.startsWith("ent-")) entityKeys.add(key);
        }

        List<String> dead = new ArrayList<>();
        Map<String, Integer> byPrefix = new TreeMap<>();
        for (String key : declared) {
            if (fromYaml.contains(key) || fromCode.contains(key)) continue;
            if (fluentReferences.contains(key) || entityKeys.contains(key)) continue;
            if (startsWithAny(key, codePrefixes) || startsWithAny(key, datasetPrefixes)) continue;

            dead.add(key);
            byPrefix.merge(key.split("-")[0], 1, Integer::sum);
        }

        System.out.println("declared messages: " + declared.size());
        System.out.println("   used from prototypes: " + count(declared, fromYaml));
        System.out.println("   used from C# literals: " + count(declared, fromCode));
        System.out.println("   referenced by other messages: " + count(declared, fluentReferences));
        System.out.println("   built by the engine (ent-*): " + entityKeys.size());
        System.out.println("   covered by a prefix assembled in code: " + codePrefixes.size() + " prefixes");
        System.out.println("   covered by a localizedDataset prefix: " + datasetPrefixes.size() + " prefixes");
        System.out.println("unused by any of the four: " + dead.size()
            + " (" + (100 * dead.size() / Math.max(1, declared.size())) + "%)");

        dead.sort(String::compareTo);
        for (String key : dead.subList(0, Math.min(dead.size(), 15))) System.out.println("   " + key);

        System.out.println("widest prefixes among them:");
        byPrefix.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .forEach(e -> System.out.println("   " + e.getKey() + "-*: " + e.getValue()));
    }

    private static String id(Object usage) throws Exception {
        return (String) usage.getClass().getMethod("getId").invoke(usage);
    }

    private static boolean startsWithAny(String key, Set<String> prefixes) {
        for (String prefix : prefixes) if (key.startsWith(prefix)) return true;
        return false;
    }

    private static int count(Set<String> declared, Set<String> used) {
        int hits = 0;
        for (String key : declared) if (used.contains(key)) hits++;
        return hits;
    }

    private static Object companion(String index) throws Exception {
        return Class.forName("com.jetbrains.rider.plugins.robustyaml." + index)
            .getField("Companion").get(null);
    }
}
