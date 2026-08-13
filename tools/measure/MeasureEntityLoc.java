import kotlin.jvm.functions.Function1;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the entity hover will actually print. The text an entity shows in the game is assembled by
 * {@code LocalizationManager.CalcEntityLoc} out of a Fluent entry — the name from the value, the
 * description and the suffix from the {@code desc} and {@code suffix} attributes — and a third of
 * those texts are not text at all but a reference to another entry, {@code ent-Crowbar = { ent-BaseCrowbar }}.
 * Reading the entry without splitting attributes off, or without following references, puts braces
 * into the popup instead of a name.
 *
 * <p>The guard is the leftover: after resolution no text may still carry a placeable that names a
 * message the same culture declares, because that is precisely a reference the resolver was supposed
 * to follow and did not. A placeable naming a message nobody declares is a broken reference in the
 * content and is only reported; so is {@code { $arg }}, which the engine fills in at runtime.
 */
public final class MeasureEntityLoc {
    private static final String PREFIX = "ent-";

    /** A placeable that names a message, the only shape the resolver is answerable for. */
    private static final Pattern REFERENCE =
        Pattern.compile("\\{[ \\t]*([A-Za-z][\\w-]*)(?:\\.([\\w-]+))?[ \\t]*}");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object index = MeasureReferences.companion("RobustLocaleIndex");
        Method messages = index.getClass().getMethod("messages", CharSequence.class);

        Class<?> localization = Class.forName("com.jetbrains.rider.plugins.robustyaml.RobustLocalization");
        Object instance = localization.getField("INSTANCE").get(null);
        Method entryAt = localization.getMethod("entryAt", CharSequence.class, int.class);
        Method resolved = localization.getMethod("resolved", String.class, int.class, Function1.class);

        Class<?> entryClass = Class.forName("com.jetbrains.rider.plugins.robustyaml.RobustLocalization$Entry");
        Method getValue = entryClass.getMethod("getValue");
        Method getAttributes = entryClass.getMethod("getAttributes");

        // culture -> id -> entry, which is how a Fluent bundle is scoped: ru-RU never reads en-US.
        Map<String, Map<String, Object>> bundles = new TreeMap<>();
        int files = 0;
        int unreadable = 0;
        for (Path file : MeasureHoles.sources(root, ".ftl")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            files++;

            Map<String, Object> bundle = bundles.computeIfAbsent(culture(file), c -> new LinkedHashMap<>());
            Map<String, Integer> found = (Map<String, Integer>) messages.invoke(index, text);
            for (Map.Entry<String, Integer> message : found.entrySet()) {
                Object entry = entryAt.invoke(instance, text, message.getValue());
                if (entry == null) {
                    unreadable++;
                    continue;
                }
                bundle.putIfAbsent(message.getKey(), entry);
            }
        }

        int entities = 0;
        int values = 0;
        int descriptions = 0;
        int suffixes = 0;
        int emptied = 0;
        List<String> leftover = new ArrayList<>();
        Map<String, Integer> dangling = new TreeMap<>();
        Map<String, Integer> runtime = new TreeMap<>();

        long start = System.currentTimeMillis();
        for (Map.Entry<String, Map<String, Object>> bundle : bundles.entrySet()) {
            Map<String, Object> byId = bundle.getValue();
            Function1<String, Object> lookup = byId::get;

            for (Map.Entry<String, Object> message : byId.entrySet()) {
                if (!message.getKey().startsWith(PREFIX)) continue;
                entities++;

                Object entry = message.getValue();
                Map<String, String> attributes = (Map<String, String>) getAttributes.invoke(entry);
                List<String> texts = new ArrayList<>();
                if (getValue.invoke(entry) != null) {
                    texts.add((String) getValue.invoke(entry));
                    values++;
                }
                if (attributes.get("desc") != null) {
                    texts.add(attributes.get("desc"));
                    descriptions++;
                }
                if (attributes.get("suffix") != null) {
                    texts.add(attributes.get("suffix"));
                    suffixes++;
                }

                for (String text : texts) {
                    String result = (String) resolved.invoke(instance, text, 0, lookup);
                    if (result.isBlank()) emptied++;

                    Matcher remaining = REFERENCE.matcher(result);
                    while (remaining.find()) {
                        Object target = byId.get(remaining.group(1));
                        String field = remaining.group(2);
                        Object body = target == null ? null
                            : field == null ? getValue.invoke(target)
                            : ((Map<String, String>) getAttributes.invoke(target)).get(field);

                        // Nothing to put in its place is the content's problem, and the engine leaves
                        // the reference standing too. Only a reference the bundle can answer and the
                        // resolver did not is a failure here.
                        if (body != null) {
                            if (leftover.size() < 10) {
                                leftover.add(bundle.getKey() + " " + message.getKey() + ": " + result);
                            }
                        } else {
                            dangling.merge(remaining.group(0), 1, Integer::sum);
                        }
                    }
                    for (String argument : arguments(result)) runtime.merge(argument, 1, Integer::sum);
                }
            }
        }
        long spent = System.currentTimeMillis() - start;

        System.out.println("ftl files: " + files + ", cultures: " + bundles.keySet());
        System.out.println("entries with no readable body: " + unreadable);
        System.out.println("ent-* entries: " + entities
            + " (value " + values + ", .desc " + descriptions + ", .suffix " + suffixes + ")");
        System.out.println("resolved to nothing: " + emptied + "   (a deliberate { \"\" } override)");
        System.out.println("references with nothing to resolve to: " + total(dangling));
        print(dangling);
        System.out.println("placeables left for the engine to fill: " + total(runtime));
        print(runtime);
        System.out.println("resolution took " + spent + " ms");

        System.out.println("LEFTOVER: " + leftover.size());
        leftover.forEach(l -> System.out.println("   " + l));
        if (!leftover.isEmpty()) System.exit(1);
    }

    /** What stays behind after resolution because the engine supplies it: `{ $user }`, `{ NUMBER($x) }`. */
    private static List<String> arguments(String text) {
        List<String> found = new ArrayList<>();
        int at = 0;
        while ((at = text.indexOf('{', at)) >= 0) {
            int end = text.indexOf('}', at);
            if (end < 0) break;
            String inside = text.substring(at + 1, end).trim();
            if (!REFERENCE.matcher(text.substring(at, end + 1)).matches()) {
                found.add(inside.startsWith("$") ? "$…" : inside.replaceAll("\\(.*", "(…)"));
            }
            at = end + 1;
        }
        return found;
    }

    private static int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static void print(Map<String, Integer> counts) {
        counts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(5)
            .forEach(e -> System.out.println("   " + e.getKey() + ": " + e.getValue()));
    }

    /** The directory right under `Locale`, which is what the engine calls a culture. */
    private static String culture(Path file) {
        for (Path current = file.getParent(); current != null; current = current.getParent()) {
            Path parent = current.getParent();
            if (parent != null && parent.getFileName().toString().equals("Locale")) {
                return current.getFileName().toString();
            }
        }
        return "?";
    }
}
