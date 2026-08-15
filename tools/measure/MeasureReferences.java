import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Runs the real indexers over a content checkout and reports what the tree highlighting would mark.
 *
 * <p>Errors are what the engine drops on load (unknown component, unresolvable parent), warnings are
 * references that only fail when used. A python model of the same logic was tried once and lied by
 * an order of magnitude, so the indexer companions are called directly.
 */
public final class MeasureReferences {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object references = companion("RobustYamlReferenceIndex");
        Method referencesOf = references.getClass().getMethod("references", CharSequence.class);

        Object ids = companion("RobustPrototypeIdIndex");
        Method prototypeIds = ids.getClass().getMethod("prototypeIds", CharSequence.class);

        Object names = companion("RobustComponentNameIndex");
        Method componentNames = names.getClass().getMethod("componentNames", CharSequence.class);

        List<Path> cs = MeasureHoles.sources(root, ".cs");
        List<Path> prototypes = MeasureHoles.prototypes(root);

        Set<String> knownComponents = new HashSet<>();
        for (Path file : cs) {
            String text = MeasureHoles.read(file);
            if (text != null) knownComponents.addAll((Set<String>) componentNames.invoke(names, text));
        }

        Set<String> knownIds = new HashSet<>();
        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text != null) knownIds.addAll(((Map<String, String>) prototypeIds.invoke(ids, text)).keySet());
        }

        long start = System.currentTimeMillis();
        Map<String, Integer> unknownComponents = new TreeMap<>();
        Map<String, Integer> unknownParents = new TreeMap<>();
        Map<String, Integer> unknownIds = new TreeMap<>();
        Set<Path> errorFiles = new TreeSet<>();
        Set<Path> warningFiles = new TreeSet<>();
        int componentRefs = 0;
        int idRefs = 0;

        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (String key : ((Map<String, Void>) referencesOf.invoke(references, text)).keySet()) {
                String value = key.substring(2);
                if (key.startsWith("c:")) {
                    componentRefs++;
                    if (!knownComponents.contains(value)) {
                        unknownComponents.merge(value, 1, Integer::sum);
                        errorFiles.add(file);
                    }
                } else if (key.startsWith("p:")) {
                    idRefs++;
                    if (!knownIds.contains(value)) {
                        unknownParents.merge(value, 1, Integer::sum);
                        errorFiles.add(file);
                    }
                } else if (key.startsWith("r:")) {
                    idRefs++;
                    if (!knownIds.contains(value)) {
                        unknownIds.merge(value, 1, Integer::sum);
                        warningFiles.add(file);
                    }
                }
            }
        }
        long spent = System.currentTimeMillis() - start;

        System.out.println("prototype files: " + prototypes.size() + ", cs files: " + cs.size());
        System.out.println("known components: " + knownComponents.size() + ", known ids: " + knownIds.size());
        System.out.println("component refs: " + componentRefs + ", id refs: " + idRefs);
        System.out.println("unknown components (ERROR): " + unknownComponents);
        System.out.println("unresolvable parents (ERROR): " + unknownParents);
        System.out.println("unknown id references (WARNING): " + unknownIds);
        System.out.println("files marked: " + errorFiles.size() + " error, " + warningFiles.size() + " warning");
        System.out.println("scan time: " + spent + " ms");
        warningFiles.forEach(file -> System.out.println("   warning: " + root.relativize(file)));
        errorFiles.forEach(file -> System.out.println("   error:   " + root.relativize(file)));
    }

    static Object companion(String simpleName) throws Exception {
        return pluginClass(simpleName).getField("Companion").get(null);
    }

    /**
     * A plugin class by its simple name. The sources are split into packages by their role in the
     * platform, and a measurement has no business tracking which package a class sits in today —
     * it calls the shipped code, not a particular file of it. Nested names work as well:
     * {@code pluginClass("RobustGuidebook$Reference")}.
     */
    static Class<?> pluginClass(String name) throws ClassNotFoundException {
        for (String pkg : PACKAGES) {
            try {
                return Class.forName(BASE + pkg + name);
            } catch (ClassNotFoundException missing) {
                // The next package, then.
            }
        }
        throw new ClassNotFoundException(BASE + "?." + name);
    }

    private static final String BASE = "com.jetbrains.rider.plugins.robustyaml.";

    private static final String[] PACKAGES = {
        "", "index.", "lookup.", "inspection.", "quickfix.", "navigation.", "documentation.",
    };
}
