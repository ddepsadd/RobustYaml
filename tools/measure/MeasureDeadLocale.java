import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    private static final Pattern TOKEN =
        Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+");

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
        Method bindings = usages.getClass().getMethod("bindings", CharSequence.class);
        Method attributes = usages.getClass().getMethod("attributes", CharSequence.class);

        // The prefixes come from the index the plugin ships, so the measurement cannot drift from it.
        Object affixIndex = companion("RobustLocaleAffixIndex");
        Method affixesOf = affixIndex.getClass().getMethod("affixes", CharSequence.class, String.class);

        Set<String> declared = new HashSet<>();
        Set<String> fluentReferences = new HashSet<>();
        List<Path> locales = new ArrayList<>();
        // Engine messages live in their own root, and a reference from one of them to a content key
        // counts just the same: reading only `Resources/Locale` calls those keys dead.
        locales.addAll(MeasureHoles.sources(root.resolve("Resources").resolve("Locale"), ".ftl"));
        Path engine = root.resolve("RobustToolbox").resolve("Resources").resolve("Locale");
        if (Files.isDirectory(engine)) locales.addAll(MeasureHoles.sources(engine, ".ftl"));
        for (Path file : locales) {
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

            datasetPrefixes.addAll((Set<String>) affixesOf.invoke(affixIndex, text, "yml"));
        }

        Set<String> fromMarkup = new HashSet<>();
        for (Path file : MeasureHoles.sources(root, ".xaml")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Object usage : (List<?>) bindings.invoke(usages, text)) fromMarkup.add(id(usage));
        }

        Set<String> fromGuidebook = new HashSet<>();
        for (Path file : MeasureHoles.sources(root, ".xml")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Object usage : (List<?>) attributes.invoke(usages, text)) fromGuidebook.add(id(usage));
        }

        Set<String> fromCode = new HashSet<>();
        Set<String> codePrefixes = new HashSet<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            for (Object usage : (List<?>) literals.invoke(usages, text)) fromCode.add(id(usage));

            codePrefixes.addAll((Set<String>) affixesOf.invoke(affixIndex, text, "cs"));
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
            if (fromMarkup.contains(key) || fromGuidebook.contains(key)) continue;
            if (covered(key, codePrefixes) || covered(key, datasetPrefixes)) continue;

            dead.add(key);
            byPrefix.merge(key.split("-")[0], 1, Integer::sum);
        }

        System.out.println("declared messages: " + declared.size());
        System.out.println("   used from prototypes: " + count(declared, fromYaml));
        System.out.println("   used from C# literals: " + count(declared, fromCode));
        System.out.println("   referenced by other messages: " + count(declared, fluentReferences));
        System.out.println("   bound from XAML: " + count(declared, fromMarkup));
        System.out.println("   named by a guidebook: " + count(declared, fromGuidebook));
        System.out.println("   built by the engine (ent-*): " + entityKeys.size());
        System.out.println("   covered by an affix assembled in code: " + codePrefixes.size() + " affixes");
        System.out.println("   covered by a localizedDataset prefix: " + datasetPrefixes.size() + " prefixes");
        System.out.println("unused by any of the four: " + dead.size()
            + " (" + (100 * dead.size() / Math.max(1, declared.size())) + "%)");

        dead.sort(String::compareTo);
        boolean all = args.length > 1 && "-all".equals(args[1]);
        for (String key : dead.subList(0, all ? dead.size() : Math.min(dead.size(), 15))) {
            System.out.println("   " + key);
        }

        // The guard: a key the plugin will grey out must be written nowhere in the checkout. Comments
        // are cut out first — a mention inside one is not a usage, and 1436 of the candidates are only
        // ever mentioned in commented-out YAML.
        Set<String> spelled = new TreeSet<>();
        List<Path> searched = new ArrayList<>();
        for (String extension : List.of(".cs", ".yml", ".xaml", ".xml")) {
            searched.addAll(MeasureHoles.sources(root, extension));
        }
        Set<String> deadSet = new HashSet<>(dead);
        for (Path file : searched) {
            if (file.toString().contains(File.separator + "Locale" + File.separator)) continue;
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            Matcher token = TOKEN.matcher(uncommented(file, text));
            while (token.find()) {
                if (deadSet.contains(token.group())) spelled.add(token.group() + "   (" + file.getFileName() + ")");
            }
        }

        System.out.println("widest prefixes among them:");
        byPrefix.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .forEach(e -> System.out.println("   " + e.getKey() + "-*: " + e.getValue()));

        System.out.println("SPELLED OUT SOMEWHERE: " + spelled.size());
        spelled.stream().limit(10).forEach(k -> System.out.println("   " + k));
        if (!spelled.isEmpty()) System.exit(1);
    }

    /** A key mentioned in a comment is not a usage, and rewriting one would be editing prose. */
    private static String uncommented(Path file, String text) {
        String name = file.toString();
        if (name.endsWith(".yml")) return text.replaceAll("(?m)#.*$", " ");
        if (name.endsWith(".xml") || name.endsWith(".xaml")) return text.replaceAll("(?s)<!--.*?-->", " ");
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static String id(Object usage) throws Exception {
        return (String) usage.getClass().getMethod("getId").invoke(usage);
    }

    /** A head ends with a dash and a tail begins with one, so each affix says which end it is. */
    private static boolean covered(String key, Set<String> affixes) {
        for (String affix : affixes) {
            if (affix.startsWith("-") ? key.endsWith(affix) : key.startsWith(affix)) return true;
        }
        return false;
    }

    private static int count(Set<String> declared, Set<String> used) {
        int hits = 0;
        for (String key : declared) if (used.contains(key)) hits++;
        return hits;
    }

    private static Object companion(String index) throws Exception {
        return MeasureReferences.pluginClass(index)
            .getField("Companion").get(null);
    }
}
