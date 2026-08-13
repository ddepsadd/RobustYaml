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
 * Guards the localization index and the LocId validation.
 *
 * <p>Message ids come from {@code RobustLocaleIndex.messages}, so this measures the shipped indexer.
 * Key names are picked the same way as in {@link MeasureScalars}: only where every
 * {@code [DataField]} with that name declares {@code LocId} — the plugin asks the backend per owner,
 * the measurement cannot.
 *
 * <p>Two rules of the engine are modelled here because they decide whether a value is a miss:
 * {@code HasMessage} cuts the id at the first dot (what follows is a Fluent attribute), and it looks
 * the id up in the default culture and then in the fallbacks, so a key from any locale counts.
 */
public final class MeasureLocalization {
    private static final Pattern KEY_VALUE = Pattern.compile("(?m)^[ \\t]*(\\w+)[ \\t]*:[ \\t]*(\\S.*?)[ \\t]*\\r?$");

    private static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z][\\w-]*");

    private static final Set<String> LOC_ID_TYPES = Set.of("LocId", "LocId?");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object index = MeasureReferences.companion("RobustLocaleIndex");
        Method messages = index.getClass().getMethod("messages", CharSequence.class);

        Class<?> localization = Class.forName("com.jetbrains.rider.plugins.robustyaml.RobustLocalization");
        Object localizationInstance = localization.getField("INSTANCE").get(null);
        Method messageAt = localization.getMethod("messageAt", CharSequence.class, int.class);

        Map<String, Integer> keys = new HashMap<>();
        Map<String, Integer> perLocale = new TreeMap<>();
        int files = 0;
        int empty = 0;
        List<String> emptyExamples = new ArrayList<>();
        for (Path file : MeasureHoles.sources(root, ".ftl")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            files++;
            Map<String, Integer> found = (Map<String, Integer>) messages.invoke(index, text);
            keys.putAll(found);
            perLocale.merge(locale(root, file), found.size(), Integer::sum);

            for (Map.Entry<String, Integer> message : found.entrySet()) {
                if (messageAt.invoke(localizationInstance, text, message.getValue()) != null) continue;
                empty++;
                if (emptyExamples.size() < 5) {
                    emptyExamples.add(message.getKey() + "   (" + root.relativize(file) + ")");
                }
            }
        }
        System.out.println("ftl files: " + files + ", message ids: " + keys.size());
        System.out.println("   per locale: " + perLocale);
        System.out.println("ids with no readable body: " + empty);
        emptyExamples.forEach(e -> System.out.println("   " + e));

        Map<String, Set<String>> typesOfKey = new HashMap<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null || !text.contains("LocId")) continue;
            for (Map.Entry<String, String> field : MeasureScalars.datafields(text).entrySet()) {
                typesOfKey.computeIfAbsent(field.getKey(), k -> new HashSet<>()).add(field.getValue());
            }
        }
        Set<String> localized = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : typesOfKey.entrySet()) {
            if (LOC_ID_TYPES.containsAll(entry.getValue())) localized.add(entry.getKey());
        }
        System.out.println("key names typed LocId only: " + localized.size());

        int values = 0;
        Map<String, List<String>> missing = new TreeMap<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            Matcher m = KEY_VALUE.matcher(text);
            while (m.find()) {
                if (!localized.contains(m.group(1))) continue;

                String value = MeasureScalars.value(m.group(2));
                if (value.isEmpty() || "!*&".indexOf(value.charAt(0)) >= 0 || value.equals("null")) continue;

                String id = value.contains(".") ? value.substring(0, value.indexOf('.')) : value;
                if (!MESSAGE_ID.matcher(id).matches()) continue;

                values++;
                if (!keys.containsKey(id)) {
                    missing.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(m.group(1) + "   (" + root.relativize(file) + ")");
                }
            }
        }

        System.out.println("LocId values checked: " + values + ", missing: " + missing.size());

        Class<?> fix = Class.forName("com.jetbrains.rider.plugins.robustyaml.ChangeLocalizationIdFix");
        Method suggest = fix.getDeclaredClasses()[0].getMethod("suggest", List.class, String.class);
        Object companion = fix.getField("Companion").get(null);
        List<String> all = new ArrayList<>(keys.keySet());

        long started = System.nanoTime();
        for (Map.Entry<String, List<String>> entry : missing.entrySet()) {
            List<String> suggestions = (List<String>) suggest.invoke(companion, all, entry.getKey());
            System.out.println("   " + entry.getKey() + " x" + entry.getValue().size()
                + "   " + entry.getValue().get(0));
            System.out.println("      quick fix: " + (suggestions.isEmpty() ? "no suggestion" : suggestions));
        }
        if (!missing.isEmpty()) {
            System.out.println("   suggestions took " + (System.nanoTime() - started) / 1_000_000 + " ms");
        }

        // A real dead link usually has no near neighbour, so the fix would look dead either way.
        // Typos are made on purpose here: every 500th key with one letter swapped must suggest it back.
        int probes = 0;
        int recovered = 0;
        long worst = 0;
        long spent = 0;
        for (int i = 0; i < all.size(); i += 500) {
            String key = all.get(i);
            if (key.length() < 6) continue;
            String typo = key.substring(0, key.length() / 2) + "x" + key.substring(key.length() / 2 + 1);

            long at = System.nanoTime();
            List<String> suggestions = (List<String>) suggest.invoke(companion, all, typo);
            long took = System.nanoTime() - at;
            spent += took;
            worst = Math.max(worst, took / 1_000_000);

            probes++;
            if (suggestions.contains(key)) recovered++;
        }
        System.out.println("typo probes: " + probes + ", suggested back: " + recovered
            + ", slowest call: " + worst + " ms, average: " + (probes == 0 ? 0 : spent / probes / 1_000_000) + " ms");
    }

    static String locale(Path root, Path file) {
        String path = root.relativize(file).toString();
        int at = path.indexOf("Locale/");
        if (at < 0) return "?";
        String rest = path.substring(at + "Locale/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
