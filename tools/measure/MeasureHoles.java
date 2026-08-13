import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Watchdog over the component name heuristic.
 *
 * <p>{@code RobustComponentNameIndex} looks for the {@code RegisterComponent} marker file-wide, so
 * every {@code *Component} class in a file that registers at least one component ends up in the
 * index. This compares the index against a per-class reading of the attribute and reports both
 * directions. Names missing from the index are a real defect; extra names only hide typos that
 * happen to match an unregistered class.
 */
public final class MeasureHoles {
    private static final Pattern DECLARATION = Pattern.compile(
        "((?:(?:public|internal|private|protected|sealed|partial|abstract|static|readonly|record|unsafe)\\s+)+)"
            + "(?:class|record|struct)\\s+(\\w+)");

    private static final Pattern PROTO_NAME = Pattern.compile("ComponentProtoName\\(\\s*\"([^\"]+)\"");

    private static final Pattern COMPONENT_ENTRY =
        Pattern.compile("(?m)^[ \\t]+-[ \\t]+type[ \\t]*:[ \\t]*\"?(\\w+)\"?");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object companion = Class.forName("com.jetbrains.rider.plugins.robustyaml.RobustComponentNameIndex")
            .getField("Companion").get(null);
        Method componentNames = companion.getClass().getMethod("componentNames", CharSequence.class);
        Method protoName = companion.getClass().getMethod("protoName", String.class);

        Set<String> indexed = new TreeSet<>();
        Set<String> registered = new TreeSet<>();
        Map<String, String> origin = new TreeMap<>();

        for (Path file : sources(root, ".cs")) {
            String text = read(file);
            if (text == null) continue;

            Set<String> fromIndex = (Set<String>) componentNames.invoke(companion, text);
            indexed.addAll(fromIndex);

            Set<String> here = new TreeSet<>();
            String blanked = Cs.blankCommentsAndLiterals(text);
            Matcher declaration = DECLARATION.matcher(blanked);
            while (declaration.find()) {
                String className = declaration.group(2);
                if (!className.endsWith("Component") || declaration.group(1).contains("abstract")) continue;
                if (!Cs.attributesBefore(blanked, declaration.start()).contains("RegisterComponent")) continue;
                here.add((String) protoName.invoke(companion, className));
            }
            // literals are blanked out above, so the proto name has to come from the original text
            Matcher proto = PROTO_NAME.matcher(text);
            while (proto.find()) here.add(proto.group(1));

            registered.addAll(here);
            for (String name : fromIndex) {
                if (!here.contains(name)) origin.putIfAbsent(name, root.relativize(file).toString());
            }
        }

        Set<String> extra = new TreeSet<>(indexed);
        extra.removeAll(registered);
        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(indexed);

        System.out.println("index names: " + indexed.size() + ", registered names: " + registered.size());
        System.out.println("MISSING (index loses a real component): " + missing.size());
        missing.forEach(name -> System.out.println("   " + name));

        System.out.println("extra (index accepts an unregistered class): " + extra.size());
        extra.forEach(name -> System.out.println("   " + name + "   <- " + origin.get(name)));

        Map<String, Integer> used = new TreeMap<>();
        for (Path file : prototypes(root)) {
            String text = read(file);
            if (text == null) continue;
            Matcher entry = COMPONENT_ENTRY.matcher(text);
            while (entry.find()) {
                if (extra.contains(entry.group(1))) used.merge(entry.group(1), 1, Integer::sum);
            }
        }
        System.out.println("extra names used in prototypes: " + used.size() + " -> " + used);

        if (!missing.isEmpty()) {
            System.out.println();
            System.out.println("The heuristic now loses components. This is the trigger for moving "
                + "component names to the ReSharper backend.");
            System.exit(1);
        }
    }

    static List<Path> sources(Path root, String extension) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(extension)).collect(Collectors.toList());
        }
    }

    static List<Path> prototypes(Path root) throws Exception {
        Path resources = root.resolve("Resources");
        if (!Files.isDirectory(resources)) return new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resources)) {
            return walk.filter(p -> p.toString().endsWith(".yml"))
                .filter(p -> p.toString().contains("Prototypes"))
                .collect(Collectors.toList());
        }
    }

    static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
