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
import java.util.TreeSet;

/**
 * Guards what a string literal of C# is taken to name. The links come from the shipped
 * {@code RobustCodeLinks.links}, so the rule measured here is the one that ships; the ids and their
 * kinds come from the prototype index the same way the IDE gets them, and the kind a class stands
 * for is read off {@code RobustDataFieldIndex} — the same {@code prototype:} key the lookup uses.
 *
 * <p>The guard is <b>UNKNOWN KIND</b>: a class named by {@code ProtoId<X>} that the checkout does
 * not declare a prototype. Every one of them does today, so an entry means the class-to-kind map
 * stopped answering — and with it the check that keeps a rename off a literal naming another kind's
 * id of the same name. The filter degrades quietly there, which is exactly why it is measured.
 *
 * <p>The rest is the content's business and is printed rather than failed. <b>WRONG KIND</b> is an
 * id declared under no kind its type stands for — ss14-wega has one, {@code ProtoId<NpcFactionPrototype>
 * RevPrototypeId = "Rev"}, where the faction is spelled {@code Revolutionary} and {@code Rev} is an
 * antag; without the kind check a rename of that antag would rewrite this line. <b>DEAD</b> is an id
 * nobody declares, most of them fixtures a test file declares in a string of its own, and
 * <b>DEAD PATH</b> a file under the resources that is not there. A state naming no frame is counted
 * beside them; ss14-wega has none of those at all.
 */
public final class MeasureCodeLinks {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Map<String, Set<String>> declared = declaredKinds(root);
        Map<String, String> kindOfClass = kindsByClass(root);

        Object links = MeasureReferences.pluginClass("RobustCodeLinks").getField("INSTANCE").get(null);
        Method of = links.getClass().getMethod("links", CharSequence.class);
        Class<?> link = MeasureReferences.pluginClass("CodeLink");
        Method kindOf = link.getMethod("getKind");
        Method valueOf = link.getMethod("getValue");
        Method classOf = link.getMethod("getPrototypeClass");
        Method spriteOf = link.getMethod("getSpritePath");

        List<Path> roots = resourceRoots(root);
        Map<String, Integer> byClass = new TreeMap<>();
        Set<String> uniqueIds = new TreeSet<>();
        Set<String> uniquePaths = new TreeSet<>();
        List<String> dead = new ArrayList<>();
        List<String> deadPaths = new ArrayList<>();
        List<String> wrongKind = new ArrayList<>();
        List<String> unknownClass = new ArrayList<>();
        int ids = 0;
        int paths = 0;
        int resolved = 0;
        int outside = 0;
        int fixtures = 0;
        int states = 0;
        List<String> deadStates = new ArrayList<>();

        long started = System.currentTimeMillis();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            for (Object found : (List<Object>) of.invoke(links, text)) {
                String value = (String) valueOf.invoke(found);
                String where = value + " in " + root.relativize(file);

                if (kindOf.invoke(found).toString().equals("SPRITE_STATE")) {
                    states++;
                    String rsi = (String) spriteOf.invoke(found);
                    if (!resolves(roots, rsi + "/" + value + ".png")) deadStates.add(rsi + " " + where);
                    continue;
                }

                if (kindOf.invoke(found).toString().equals("PATH")) {
                    paths++;
                    uniquePaths.add(value);
                    if (resolves(roots, value)) resolved++;
                    // A route of the admin API (`/admin/info`) and a prefix built up later
                    // (`/Fonts/NotoSans/NotoSansSymbols-`) are absolute strings that name no
                    // resource at all. They cost nothing — a path that resolves to nothing offers
                    // no jump and is never indexed — so they are counted, not listed.
                    else if (underResources(roots, value)) deadPaths.add(where);
                    else outside++;
                    continue;
                }

                ids++;
                uniqueIds.add(value);
                String className = (String) classOf.invoke(found);
                byClass.merge(className == null ? "EntProtoId" : className, 1, Integer::sum);

                Set<String> kinds = declared.get(value);
                if (kinds == null) {
                    if (isTest(file)) fixtures++; else dead.add(where);
                    continue;
                }
                // `EntProtoId` is always an entity; the parameter of its generic form constrains a
                // component, not a kind.
                String wanted = className == null ? "entity" : kindOfClass.get(className);
                if (wanted == null) {
                    unknownClass.add(className + " (" + value + ")");
                } else if (!kinds.contains(wanted)) {
                    wrongKind.add(where + ": declared " + kinds + ", named as " + wanted);
                }
            }
        }

        System.out.println("path literals: " + paths + ", distinct: " + uniquePaths.size()
            + ", resolving: " + resolved + ", naming no resource: " + outside);
        System.out.println("state literals: " + states + ", of them naming no frame: " + deadStates.size());
        for (String line : deadStates) System.out.println("   " + line);
        System.out.println("id literals: " + ids + ", distinct: " + uniqueIds.size());
        for (Map.Entry<String, Integer> entry : byClass.entrySet()) {
            System.out.printf("  %5d  %s%n", entry.getValue(), entry.getKey());
        }
        System.out.println("elapsed: " + (System.currentTimeMillis() - started) + " ms");

        System.out.println("DEAD PATH: " + deadPaths.size());
        for (String line : deadPaths) System.out.println("   " + line);
        System.out.println("DEAD: " + dead.size() + ", fixtures declared by a test: " + fixtures);
        for (String line : dead) System.out.println("   " + line);
        System.out.println("WRONG KIND: " + wrongKind.size());
        for (String line : wrongKind) System.out.println("   " + line);
        System.out.println("UNKNOWN KIND: " + new TreeSet<>(unknownClass));
        if (!unknownClass.isEmpty()) System.exit(1);
    }

    /** Every kind an id is declared under, read off the shipped prototype id index. */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> declaredKinds(Path root) throws Exception {
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
        return kinds;
    }

    /** The class a kind is declared by, inverted — what {@code ProtoId<X>} has to be read through. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> kindsByClass(Path root) throws Exception {
        Object fields = MeasureReferences.companion("RobustDataFieldIndex");
        Method index = fields.getClass().getMethod("index", CharSequence.class);
        String prefix = (String) MeasureReferences.pluginClass("RobustDataFieldIndex")
            .getDeclaredField("PROTOTYPE_KEY").get(null);

        Map<String, String> byClass = new HashMap<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Map.Entry<String, String> entry :
                ((Map<String, String>) index.invoke(fields, text)).entrySet()) {
                if (!entry.getKey().startsWith(prefix)) continue;
                byClass.putIfAbsent(entry.getValue(), entry.getKey().substring(prefix.length()));
            }
        }
        return byClass;
    }

    /** The roots a leading slash is read from, as {@code RobustResources} collects them. */
    private static List<Path> resourceRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        for (Path candidate : List.of(root.resolve("Resources"),
            root.resolve("RobustToolbox").resolve("Resources"))) {
            if (Files.isDirectory(candidate)) roots.add(candidate);
        }
        return roots;
    }

    /**
     * Whether the first segment names a directory of the resources. What it tells apart is a path
     * that misses its file from a string that was never one: the admin API answers on `/admin/info`,
     * and no root has an `admin` in it.
     */
    private static boolean underResources(List<Path> roots, String value) {
        String relative = value.startsWith("/") ? value.substring(1) : value;
        int slash = relative.indexOf('/');
        if (slash <= 0) return false;
        String head = relative.substring(0, slash);
        for (Path root : roots) {
            if (Files.isDirectory(root.resolve(head))) return true;
        }
        return false;
    }

    /** A fixture of a test declares its prototypes in a string of its own, not in the content. */
    private static boolean isTest(Path file) {
        String path = file.toString();
        return path.contains("Tests") || path.contains("Benchmarks");
    }

    private static boolean resolves(List<Path> roots, String value) {
        String relative = value.startsWith("/") ? value.substring(1) : value;
        if (relative.isEmpty()) return false;
        for (Path root : roots) {
            if (Files.exists(root.resolve(relative))) return true;
        }
        return false;
    }
}
