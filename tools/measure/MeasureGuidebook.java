import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Guards what a guidebook document names. The ids come from the shipped
 * {@code RobustGuidebook.references}, so the tag and attribute rules measured here are the ones that
 * ship; the kinds come from the prototype index the same way the IDE gets them.
 *
 * <p>The guard is <b>WRONG KIND</b>: an id declared in the checkout, but under a kind the embed
 * cannot render, means the attribute was mapped onto the wrong kind — the plugin would then paint an
 * error over a page that works. An id nobody declares at all is a fault of the content and is
 * printed as <b>DEAD</b>; ss14-wega has exactly one, {@code MachineTraversalDistorter}, which
 * {@code migration.yml} lists as removed.
 */
public final class MeasureGuidebook {
    private static final String GUIDEBOOK_DIR = "ServerInfo";

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object index = MeasureReferences.companion("RobustPrototypeIdIndex");
        Method prototypeIds = index.getClass().getMethod("prototypeIds", CharSequence.class);

        Map<String, Set<String>> kinds = new HashMap<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Map.Entry<String, String> entry :
                ((Map<String, String>) prototypeIds.invoke(index, text)).entrySet()) {
                Set<String> found = kinds.computeIfAbsent(entry.getKey(), k -> new HashSet<>());
                for (String site : entry.getValue().split(";")) {
                    int at = site.indexOf('@');
                    if (at > 0) found.add(site.substring(0, at));
                }
            }
        }

        Class<?> guidebook = MeasureReferences.pluginClass("RobustGuidebook");
        Object instance = guidebook.getField("INSTANCE").get(null);
        Method references = guidebook.getMethod("references", CharSequence.class);
        Method id = MeasureReferences.pluginClass("RobustGuidebook$Reference")
            .getMethod("getId");
        Method kindOf = MeasureReferences.pluginClass("RobustGuidebook$Reference")
            .getMethod("getKind");

        List<Path> documents = documents(root);
        Map<String, Integer> byKind = new TreeMap<>();
        Set<String> unique = new HashSet<>();
        List<String> dead = new ArrayList<>();
        List<String> wrongKind = new ArrayList<>();
        int total = 0;

        long started = System.currentTimeMillis();
        for (Path file : documents) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Object reference : (List<Object>) references.invoke(instance, text)) {
                String value = (String) id.invoke(reference);
                String kind = (String) kindOf.invoke(reference);
                total++;
                unique.add(value);
                byKind.merge(kind, 1, Integer::sum);

                Set<String> declared = kinds.get(value);
                if (declared == null) {
                    dead.add(value + " (" + kind + ") in " + root.relativize(file));
                } else if (!declared.contains(kind)) {
                    wrongKind.add(value + " is " + declared + ", embedded as " + kind
                        + " in " + root.relativize(file));
                }
            }
        }

        System.out.println("documents: " + documents.size());
        System.out.println("embeds: " + total + ", distinct ids: " + unique.size());
        for (Map.Entry<String, Integer> entry : byKind.entrySet()) {
            System.out.printf("  %5d  %s%n", entry.getValue(), entry.getKey());
        }
        System.out.println("elapsed: " + (System.currentTimeMillis() - started) + " ms");
        System.out.println("DEAD: " + dead.size());
        for (String line : dead) System.out.println("   " + line);
        System.out.println("WRONG KIND: " + wrongKind.size());
        for (String line : wrongKind) System.out.println("   " + line);
        if (!wrongKind.isEmpty()) System.exit(1);
    }

    /** Every XML under a `ServerInfo` directory — where the roots provider looks for the guidebook. */
    private static List<Path> documents(Path root) throws Exception {
        List<Path> found = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".xml"))
                .filter(path -> {
                    for (Path part : path) {
                        if (part.toString().equals(GUIDEBOOK_DIR)) return true;
                    }
                    return false;
                })
                .forEach(found::add);
        }
        return found;
    }
}
