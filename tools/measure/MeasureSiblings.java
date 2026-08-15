import kotlin.jvm.functions.Function1;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the sibling rule: a message id nobody declares, standing among neighbours that resolve, is
 * a typo rather than a value of another kind. The decision comes from the shipped
 * {@code RobustValidation.deadSiblings}; only the grouping into mappings is done here, by
 * indentation, because PSI is not available outside the IDE.
 *
 * <p>The guard is <b>FALSE POSITIVES</b>: an id the plugin calls dead must not be declared anywhere
 * in the checkout as something else. A prototype id or a component name reported as a missing
 * message means an exclusion regressed — that is exactly how {@code flavor: raw-egg} was caught,
 * where the flavours of {@code Flavors/flavors.yml} are spelled in the same kebab case as messages.
 */
public final class MeasureSiblings {
    private static final Pattern DECLARATION = Pattern.compile("(?m)^([A-Za-z][\\w-]*)[ \\t]*=");

    /** `key: value`, with or without a leading dash, comment and quotes stripped. */
    private static final Pattern KEY_VALUE =
        Pattern.compile("^[ \\t]*(?:-[ \\t]+)?([A-Za-z][\\w-]*)[ \\t]*:[ \\t]*\"?([^\"#\\n]*?)\"?[ \\t]*(?:#.*)?$");

    /** A bare element of a sequence. */
    private static final Pattern ITEM = Pattern.compile("^[ \\t]*-[ \\t]+\"?([A-Za-z][\\w-]*)\"?[ \\t]*$");

    private static final Set<String> ID_KEYS = Set.of("id", "parent", "proto", "prototype", "entity");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Set<String> messages = new HashSet<>();
        for (Path file : MeasureHoles.sources(root, ".ftl")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            Matcher matcher = DECLARATION.matcher(text);
            while (matcher.find()) messages.add(matcher.group(1));
        }

        Object ids = MeasureReferences.companion("RobustPrototypeIdIndex");
        Method prototypeIds = ids.getClass().getMethod("prototypeIds", CharSequence.class);
        Set<String> prototypes = new HashSet<>();
        List<Path> files = new ArrayList<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            files.add(file);
            prototypes.addAll(((Map<String, String>) prototypeIds.invoke(ids, text)).keySet());
        }

        Class<?> validation = MeasureReferences.pluginClass("RobustValidation");
        Object instance = validation.getField("INSTANCE").get(null);
        Method deadSiblings = validation.getMethod("deadSiblings", List.class, Function1.class, Function1.class);
        Function1<String, Boolean> declaresMessage = messages::contains;
        Function1<String, Boolean> declaresPrototype = prototypes::contains;

        Set<String> componentNames = componentNames(root);

        Map<String, List<String>> findings = new TreeMap<>();
        int total = 0;
        int falsePositives = 0;
        List<String> wrong = new ArrayList<>();
        for (Path file : files) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (List<String> mapping : mappings(text)) {
                List<Integer> dead = (List<Integer>) deadSiblings.invoke(
                    instance, mapping, declaresMessage, declaresPrototype);
                for (int at : dead) {
                    String id = mapping.get(at).trim();
                    total++;
                    findings.computeIfAbsent(root.relativize(file).toString(), k -> new ArrayList<>()).add(id);
                    if (prototypes.contains(id) || componentNames.contains(id) || messages.contains(id)) {
                        falsePositives++;
                        if (wrong.size() < 10) wrong.add(id + " in " + root.relativize(file));
                    }
                }
            }
        }

        System.out.println("declared messages: " + messages.size());
        System.out.println("prototype ids: " + prototypes.size());
        System.out.println("files scanned: " + files.size());
        System.out.println("dead siblings: " + total + " in " + findings.size() + " files");
        for (Map.Entry<String, List<String>> entry : findings.entrySet()) {
            System.out.printf("  %4d  %s%n", entry.getValue().size(), entry.getKey());
            for (String id : entry.getValue().subList(0, Math.min(4, entry.getValue().size()))) {
                System.out.println("        " + id);
            }
        }
        System.out.println("FALSE POSITIVES: " + falsePositives);
        for (String w : wrong) System.out.println("   " + w);
        if (falsePositives > 0) System.exit(1);
    }

    /**
     * The message-shaped sides of every mapping, grouped the way PSI would group them: a mapping is
     * the run of lines sharing a parent. Keys of an id mapping are dropped whole, as the plugin does.
     */
    private static List<List<String>> mappings(String text) {
        Map<Integer, List<String>> byParent = new LinkedHashMap<>();
        Deque<int[]> stack = new ArrayDeque<>();
        String[] lines = text.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = line.length() - stripLeading(line).length();
            while (!stack.isEmpty() && stack.peek()[0] >= indent) stack.pop();
            int parent = stack.isEmpty() ? -1 : stack.peek()[1];
            stack.push(new int[] {indent, n});

            Matcher keyValue = KEY_VALUE.matcher(line);
            Matcher item = ITEM.matcher(line);
            List<String> sides = byParent.computeIfAbsent(parent, k -> new ArrayList<>());
            if (keyValue.matches()) {
                if (ID_KEYS.contains(keyValue.group(1))) continue;
                sides.add(keyValue.group(1));
                if (!keyValue.group(2).isEmpty()) sides.add(keyValue.group(2));
            } else if (item.matches()) {
                sides.add(item.group(1));
            }
        }
        return new ArrayList<>(byParent.values());
    }

    private static String stripLeading(String line) {
        int at = 0;
        while (at < line.length() && (line.charAt(at) == ' ' || line.charAt(at) == '\t')) at++;
        return line.substring(at);
    }

    /** Component names as the plugin sees them, so one reported as a message counts as a miss. */
    @SuppressWarnings("unchecked")
    private static Set<String> componentNames(Path root) throws Exception {
        Object index = MeasureReferences.companion("RobustComponentNameIndex");
        Method names = index.getClass().getMethod("componentNames", CharSequence.class);
        Set<String> found = new HashSet<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            found.addAll((Set<String>) names.invoke(index, text));
        }
        return found;
    }
}
