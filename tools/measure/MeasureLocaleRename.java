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
 * What a rename of a localization message has to touch, and where it cannot reach.
 *
 * <p>The key is written down in four places — the declarations of every culture, the values of
 * prototypes, string literals in C# and placeables of other messages — and the rename is only honest
 * if it rewrites all of them. Everything here is measured through the shipped indexers
 * ({@code RobustLocaleIndex}, {@code RobustLocaleUsageIndex}, {@code RobustYamlValueIndex}) rather
 * than through a retelling of them, so the numbers move when the plugin does.
 *
 * <p>The guard is the C# scanner: it steps over comments, so it must never find a literal a plain
 * regex over the same file does not. Anything extra is a scan that lost its place, and a rename
 * would then rewrite text that is not a key at all.
 */
public final class MeasureLocaleRename {
    /** What a regex sees without knowing code from prose — the upper bound for the scanner. */
    private static final Pattern NAIVE =
        Pattern.compile("\"([A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+)\"");

    private static final Pattern PREFIX = Pattern.compile("\"([A-Za-z][\\w-]*-)(?:\\{|\"\\s*\\+)");

    private static final Pattern DATASET_PREFIX =
        Pattern.compile("(?m)^\\s*prefix:\\s*\"?([A-Za-z][\\w-]*-)\"?\\s*$");

    private static final String ENTITY_PREFIX = "ent-";

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object locale = companion("RobustLocaleIndex");
        Method messages = locale.getClass().getMethod("messages", CharSequence.class);

        Object usages = companion("RobustLocaleUsageIndex");
        Method literals = usages.getClass().getMethod("literals", CharSequence.class);
        Method placeables = usages.getClass().getMethod("placeables", CharSequence.class);

        Object values = companion("RobustYamlValueIndex");
        Method yamlValues = values.getClass().getMethod("values", CharSequence.class);

        long started = System.currentTimeMillis();

        // A message is declared once per culture, and a rename that touched a single one would
        // split it in two.
        Map<String, Integer> cultures = new HashMap<>();
        Map<String, Integer> hits = new HashMap<>();
        int ftlFiles = 0;
        for (Path file : MeasureHoles.sources(root.resolve("Resources").resolve("Locale"), ".ftl")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            ftlFiles++;
            @SuppressWarnings("unchecked")
            Map<String, ?> declared = (Map<String, ?>) messages.invoke(locale, text);
            for (String id : declared.keySet()) cultures.merge(id, 1, Integer::sum);

            for (Object usage : (List<?>) placeables.invoke(usages, text)) {
                hits.merge(id(usage), 1, Integer::sum);
            }
        }

        int csFiles = 0;
        int extra = 0;
        List<String> extras = new ArrayList<>();
        Set<String> codePrefixes = new HashSet<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            csFiles++;
            Matcher p = PREFIX.matcher(text);
            while (p.find()) codePrefixes.add(p.group(1));

            Set<String> naive = new HashSet<>();
            Matcher m = NAIVE.matcher(text);
            while (m.find()) naive.add(m.group(1));

            for (Object usage : (List<?>) literals.invoke(usages, text)) {
                String id = id(usage);
                hits.merge(id, 1, Integer::sum);

                // The scanner may find less than the regex — a literal in a comment is not a usage —
                // but never more.
                if (!naive.contains(id)) {
                    extra++;
                    if (extras.size() < 10) extras.add(file.getFileName() + ": " + id);
                }
            }
        }

        Set<String> datasetPrefixes = new HashSet<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            @SuppressWarnings("unchecked")
            Map<String, ?> keys = (Map<String, ?>) yamlValues.invoke(values, text);
            for (String key : keys.keySet()) hits.merge(key, 1, Integer::sum);

            Matcher d = DATASET_PREFIX.matcher(text);
            while (d.find()) datasetPrefixes.add(d.group(1));
        }

        long elapsed = System.currentTimeMillis() - started;

        int multiCulture = 0;
        int unused = 0;
        int assembled = 0;
        int entity = 0;
        int widest = 0;
        String widestId = "";
        Map<Integer, Integer> spread = new TreeMap<>();
        for (Map.Entry<String, Integer> declaration : cultures.entrySet()) {
            String id = declaration.getKey();
            if (declaration.getValue() > 1) multiCulture++;

            int edits = declaration.getValue() + hits.getOrDefault(id, 0);
            spread.merge(Math.min(edits, 10), 1, Integer::sum);
            if (edits > widest) {
                widest = edits;
                widestId = id;
            }

            // A key with usages of its own is renamed in full whatever its prefix looks like; the
            // blind spot is the key whose only caller assembles the name at runtime.
            if (hits.getOrDefault(id, 0) > 0) continue;

            unused++;
            if (id.startsWith(ENTITY_PREFIX)) entity++;
            else if (startsWithAny(id, codePrefixes) || startsWithAny(id, datasetPrefixes)) assembled++;
        }

        System.out.println("scanned " + ftlFiles + " .ftl and " + csFiles + " .cs in " + elapsed + " ms");
        System.out.println("declared messages: " + cultures.size());
        System.out.println("   declared in more than one culture: " + multiCulture);
        System.out.println("   with no usage to follow: " + unused);
        System.out.println("widest rename: '" + widestId + "' — " + widest + " places");
        System.out.println("edits per rename:");
        spread.forEach((edits, count) ->
            System.out.println("   " + (edits == 10 ? "10+" : String.valueOf(edits)) + ": " + count));

        System.out.println("cannot be renamed in full:");
        System.out.println("   built by the engine (" + ENTITY_PREFIX + "*): " + entity);
        System.out.println("   assembled from a prefix in code or a dataset: " + assembled);

        System.out.println("literals the scanner found and a plain regex did not: " + extra);
        for (String sample : extras) System.out.println("   " + sample);
        if (extra > 0) {
            System.out.println("FAIL: the C# scanner is reading text that is not a literal");
            System.exit(1);
        }
    }

    private static String id(Object usage) throws Exception {
        return (String) usage.getClass().getMethod("getId").invoke(usage);
    }

    private static boolean startsWithAny(String key, Set<String> prefixes) {
        for (String prefix : prefixes) if (key.startsWith(prefix)) return true;
        return false;
    }

    private static Object companion(String index) throws Exception {
        return Class.forName("com.jetbrains.rider.plugins.robustyaml." + index)
            .getField("Companion").get(null);
    }
}
