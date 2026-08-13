import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimates how noisy a "required datafield is not set" inspection would be.
 *
 * <p>The engine throws {@code RequiredFieldNotMappedException} from generated deserialization code,
 * but only after inheritance is merged ({@code ComponentRegistrySerializer} is an
 * {@code ITypeInheritanceHandler}) and never for {@code abstract: true} prototypes, which are not
 * instantiated at all. Both have to be modelled here or the count is meaningless.
 */
public final class MeasureRequired {
    private static final Pattern DECLARATION = Pattern.compile(
        "(?m)^\ufeff?-[ \\t]+type[ \\t]*:[ \\t]*(\\w+)[ \\t]*(?:#.*)?$");

    private static final Pattern ID = Pattern.compile("(?m)^\ufeff?[ ]{0,2}id[ \\t]*:[ \\t]*\"?([^\\s\"#]+)\"?");
    private static final Pattern ABSTRACT = Pattern.compile("(?m)^\ufeff?[ ]{0,2}abstract[ \\t]*:[ \\t]*true");
    private static final Pattern PARENT = Pattern.compile(
        "(?m)^\ufeff?[ ]{0,2}parent[ \\t]*:[ \\t]*(?:\\[([^\\]\\r\\n]*)\\]|\"?([^\\s\"#\\[\\]]+)\"?)");

    /** parent: followed by a block sequence, as in MobVulpkanin. */
    private static final Pattern BLOCK_PARENT = Pattern.compile(
        "(?m)^\ufeff?[ ]{0,2}parent[ \\t]*:[ \\t]*(?:#.*)?\\r?\\n"
            + "((?:[ \\t]*-[ \\t]*\"?[^\\s\"#\\r\\n]+\"?[ \\t]*(?:#.*)?\\r?\\n?)+)");

    private static final Pattern BLOCK_ITEM = Pattern.compile("(?m)^[ \\t]*-[ \\t]*\"?([^\\s\"#\\r\\n]+)\"?");

    private static final Pattern COMPONENT = Pattern.compile("(?m)^[ \\t]+-[ \\t]+type[ \\t]*:[ \\t]*\"?(\\w+)\"?");
    private static final Pattern COMPONENT_KEY = Pattern.compile("(?m)^[ \\t]{4,}(\\w+)[ \\t]*:");

    /** One prototype declaration, sliced out of a file by offsets. */
    static final class Declaration {
        String id;
        String kind;
        boolean isAbstract;
        List<String> parents = new ArrayList<>();
        Map<String, Set<String>> componentKeys = new HashMap<>();
        Path file;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object fields = MeasureReferences.companion("RobustDataFieldIndex");
        Method index = fields.getClass().getMethod("index", CharSequence.class);
        Method parseBases = fields.getClass().getMethod("parseBases", String.class);
        Method parseRequired = fields.getClass().getMethod("parseRequired", String.class);

        Map<String, List<String>> classValues = new HashMap<>();
        Map<String, String> componentClass = new HashMap<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Map.Entry<String, String> e : ((Map<String, String>) index.invoke(fields, text)).entrySet()) {
                if (e.getKey().startsWith("class:")) {
                    classValues.computeIfAbsent(e.getKey().substring(6), k -> new ArrayList<>()).add(e.getValue());
                } else if (e.getKey().startsWith("component:")) {
                    componentClass.putIfAbsent(e.getKey().substring(10), e.getValue());
                }
            }
        }

        Map<String, Set<String>> requiredOf = new HashMap<>();
        for (String component : componentClass.keySet()) {
            Set<String> required = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(componentClass.get(component));
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!seen.add(current) || seen.size() > 32) continue;
                for (String value : classValues.getOrDefault(current, List.of())) {
                    required.addAll((List<String>) parseRequired.invoke(fields, value));
                    queue.addAll((List<String>) parseBases.invoke(fields, value));
                }
            }
            if (!required.isEmpty()) requiredOf.put(component, required);
        }

        Map<String, Declaration> byId = new HashMap<>();
        List<Declaration> all = new ArrayList<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Declaration declaration : declarations(text, file)) {
                all.add(declaration);
                if (declaration.id != null) byId.putIfAbsent(declaration.id, declaration);
            }
        }

        Map<String, Integer> missing = new TreeMap<>();
        Map<String, String> example = new TreeMap<>();
        int checkedDeclarations = 0;
        for (Declaration declaration : all) {
            if (declaration.isAbstract || !"entity".equals(declaration.kind)) continue;
            checkedDeclarations++;
            for (Map.Entry<String, Set<String>> component : declaration.componentKeys.entrySet()) {
                Set<String> required = requiredOf.get(component.getKey());
                if (required == null) continue;
                Set<String> present = inheritedKeys(declaration, component.getKey(), byId);
                for (String field : required) {
                    if (!present.contains(field)) {
                        String label = component.getKey() + "." + field;
                        missing.merge(label, 1, Integer::sum);
                        example.putIfAbsent(label, declaration.id + "  (" + root.relativize(declaration.file) + ")");
                    }
                }
            }
        }

        System.out.println("components with required fields: " + requiredOf.size());
        System.out.println("concrete entity declarations checked: " + checkedDeclarations + " of " + all.size());
        int total = missing.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("missing required values: " + total + " across " + missing.size() + " field(s)");
        missing.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(25)
            .forEach(e -> System.out.println("   " + e.getKey() + " x" + e.getValue()
                + "   e.g. " + example.get(e.getKey())));
    }

    /** Keys of one component, merged over the parent chain the way the engine merges them. */
    static Set<String> inheritedKeys(Declaration start, String component, Map<String, Declaration> byId) {
        Set<String> keys = new HashSet<>();
        Deque<Declaration> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Declaration current = queue.poll();
            if (current.id != null && !seen.add(current.id)) continue;
            if (seen.size() > 64) break;
            keys.addAll(current.componentKeys.getOrDefault(component, Set.of()));
            for (String parent : current.parents) {
                Declaration next = byId.get(parent);
                if (next != null) queue.add(next);
            }
        }
        return keys;
    }

    static List<Declaration> declarations(String text, Path file) {
        List<Declaration> result = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        Matcher m = DECLARATION.matcher(text);
        int previous = -1;
        while (m.find()) {
            if (previous >= 0) spans.add(new int[] {previous, m.start()});
            previous = m.start();
            kinds.add(m.group(1));
        }
        if (previous >= 0) spans.add(new int[] {previous, text.length()});

        for (int i = 0; i < spans.size(); i++) {
            String body = text.substring(spans.get(i)[0], spans.get(i)[1]);
            Declaration declaration = new Declaration();
            declaration.kind = kinds.get(i);
            declaration.file = file;
            declaration.isAbstract = ABSTRACT.matcher(body).find();

            Matcher id = ID.matcher(body);
            if (id.find()) declaration.id = id.group(1);

            Matcher parent = PARENT.matcher(body);
            if (parent.find()) {
                if (parent.group(1) != null) {
                    for (String part : parent.group(1).split(",")) {
                        String trimmed = part.trim().replaceAll("^\"|\"$", "");
                        if (!trimmed.isEmpty()) declaration.parents.add(trimmed);
                    }
                } else {
                    declaration.parents.add(parent.group(2));
                }
            }

            Matcher block = BLOCK_PARENT.matcher(body);
            if (block.find()) {
                Matcher item = BLOCK_ITEM.matcher(block.group(1));
                while (item.find()) declaration.parents.add(item.group(1));
            }

            collectComponents(body, declaration);
            result.add(declaration);
        }
        return result;
    }

    static void collectComponents(String body, Declaration declaration) {
        Matcher component = COMPONENT.matcher(body);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int previous = -1;
        String previousName = null;
        while (component.find()) {
            if (previous >= 0) {
                spans.add(new int[] {previous, component.start()});
                names.add(previousName);
            }
            previous = component.end();
            previousName = component.group(1);
        }
        if (previous >= 0) {
            spans.add(new int[] {previous, body.length()});
            names.add(previousName);
        }

        for (int i = 0; i < spans.size(); i++) {
            Set<String> keys = declaration.componentKeys
                .computeIfAbsent(names.get(i), k -> new HashSet<>());
            Matcher key = COMPONENT_KEY.matcher(body.substring(spans.get(i)[0], spans.get(i)[1]));
            while (key.find()) keys.add(key.group(1));
        }
    }
}
