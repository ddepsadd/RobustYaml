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
 * What a check on {@code state:} would say, and whether it may be said at all.
 *
 * <p>Of the values of {@code state:} in the checkout, most stand in a prototype that never names a
 * sprite: the {@code sprite:} is on an ancestor, and merging happens in
 * {@code ComponentRegistrySerializer} before anything is read. So the frame a state names cannot be
 * found without walking the chain of parents, and this walks it the way the plugin does — nearest
 * first: the layer's own {@code sprite:}, then the component's, then the same component in every
 * ancestor, accumulating and to the end of the chain rather than stopping at the first one, because
 * bases like {@code BaseItem} are abstract and the sprite often sits several links up.
 *
 * <p>Silence is the point of most of the rules here. Where a path cannot be found, or where the
 * `.rsi` it names is not in the checkout, nothing is claimed: "the sprite is unknown" and "the state
 * is missing" are different answers, and only the second may be painted red. What the guard watches
 * is that the second never gets said by mistake — every reported state is printed with the file that
 * declares it and the directory it was checked against, so a false positive is visible rather than
 * counted.
 *
 * <p>The reading of {@code meta.json} is the shipped one ({@code RobustSprites.states}), not a
 * retelling of it.
 */
public final class MeasureStates {
    /** A declaration, kept as a tree so ownership is asked of the structure, not of columns. */
    static final class Declaration {
        String id;
        String kind;
        Block block;
        Path file;
        List<String> parents = new ArrayList<>();
    }

    static final class Finding {
        String state;
        String sprite;
        Path file;
        int line;
        List<String> declared;
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        long started = System.currentTimeMillis();

        Class<?> sprites = Class.forName("com.jetbrains.rider.plugins.robustyaml.lookup.RobustSprites");
        Method statesOf = sprites.getMethod("states", CharSequence.class);

        List<Path> roots = new ArrayList<>();
        for (Path candidate : List.of(root.resolve("Resources"),
            root.resolve("RobustToolbox").resolve("Resources"))) {
            if (Files.isDirectory(candidate)) roots.add(candidate);
        }

        Map<String, List<Declaration>> byId = new HashMap<>();
        List<Declaration> all = new ArrayList<>();
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            Block document = Block.parse(text);
            for (Block item : document.children) {
                if (item.key != null) continue;
                String kind = item.scalar("type");
                if (kind == null) continue;

                Declaration declaration = new Declaration();
                declaration.kind = kind;
                declaration.id = item.scalar("id");
                declaration.block = item;
                declaration.file = file;
                collectParents(item, declaration.parents);
                all.add(declaration);
                if (declaration.id != null) {
                    byId.computeIfAbsent(declaration.id, k -> new ArrayList<>()).add(declaration);
                }
            }
        }

        int total = 0;
        int abstractSkipped = 0;
        int layered = 0;
        int noSprite = 0;
        int noRsi = 0;
        int inherited = 0;
        int known = 0;
        List<Finding> dead = new ArrayList<>();
        Map<Path, List<String>> statesCache = new HashMap<>();

        for (Declaration declaration : all) {
            // An abstract prototype is never instantiated, and its sprite may just as well come
            // from a descendant as from an ancestor: `BenchBaseMiddle` declares `state: middle`
            // and leaves `sprite:` to the benches that inherit it. `RobustRequiredFields` steps
            // around abstracts for the same reason.
            if ("true".equalsIgnoreCase(String.valueOf(declaration.block.scalar("abstract")))) {
                abstractSkipped++;
                continue;
            }
            Block components = declaration.block.child("components");
            List<Block> pending = new ArrayList<>();
            if (components != null) {
                for (Block item : components.children) {
                    if (item.key == null) pending.add(item);
                }
            }
            for (Block component : pending) {
                String name = component.scalar("type");
                if (name == null) continue;
                List<Block[]> found = new ArrayList<>();
                visit(component, component, found);

                for (Block[] pair : found) {
                    total++;
                    Block owner = pair[0];
                    Block state = pair[1];

                    // A state written beside `layers:` is not read at all: SpriteComponent turns
                    // the component's own state into a layer only `if (layerDatums.Count == 0)`.
                    // Layers merge along the chain, so an ancestor's list silences it too.
                    if (owner == component && hasLayers(byId, declaration, name, new HashSet<>())) {
                        layered++;
                        continue;
                    }

                    String sprite = owner.scalar("sprite");
                    if (sprite == null) sprite = component.scalar("sprite");
                    if (sprite == null) {
                        sprite = ancestorSprite(byId, declaration, name, new HashSet<>());
                        if (sprite != null) inherited++;
                    }
                    if (sprite == null) {
                        noSprite++;
                        continue;
                    }

                    Path rsi = resolve(roots, sprite);
                    if (rsi == null || !Files.isDirectory(rsi)) {
                        noRsi++;
                        continue;
                    }
                    List<String> declared = statesCache.computeIfAbsent(rsi, directory -> {
                        Path meta = directory.resolve("meta.json");
                        String text = Files.isRegularFile(meta) ? MeasureHoles.read(meta) : null;
                        if (text == null) return List.of();
                        try {
                            @SuppressWarnings("unchecked")
                            List<String> read = (List<String>) statesOf.invoke(null, text);
                            return read;
                        } catch (Exception e) {
                            return List.of();
                        }
                    });
                    if (declared.isEmpty()) {
                        noRsi++;
                        continue;
                    }
                    if (declared.contains(state.value)) {
                        known++;
                        continue;
                    }
                    Finding finding = new Finding();
                    finding.state = state.value;
                    finding.sprite = sprite;
                    finding.file = declaration.file;
                    finding.line = state.line;
                    finding.declared = declared;
                    dead.add(finding);
                }
            }
        }

        System.out.printf("declarations: %d (abstract, skipped: %d), values of state: %d%n",
            all.size(), abstractSkipped, total);
        System.out.printf("checked against an rsi: %d (of them by an inherited sprite: %d)%n",
            known + dead.size(), inherited);
        System.out.printf("silent - the state stands beside layers and is never read: %d%n", layered);
        System.out.printf("silent - no sprite in scope: %d%n", noSprite);
        System.out.printf("silent - the rsi is not in the checkout: %d%n", noRsi);
        System.out.printf("STATES NOT IN THE RSI: %d%n", dead.size());

        Map<String, Integer> perFile = new TreeMap<>();
        for (Finding finding : dead) {
            perFile.merge(finding.file.getFileName().toString(), 1, Integer::sum);
        }
        int printed = 0;
        for (Finding finding : dead) {
            if (printed++ >= 40) {
                System.out.printf("   ... and %d more%n", dead.size() - 40);
                break;
            }
            System.out.printf("   %s:%d  state '%s' not in %s  (declared: %s)%n",
                root.relativize(finding.file), finding.line, finding.state, finding.sprite,
                preview(finding.declared));
        }
        System.out.printf("files with findings: %d%n", perFile.size());
        System.out.printf("took %d ms%n", System.currentTimeMillis() - started);

        // The findings themselves are content and are printed, not fatal — all three on this
        // checkout are real (`corgi_eyes_displacement` is absent from a directory that declares
        // `corgi_head` and `corgi_belt`; `teg.rsi` holds one `icon.png` and no `equipped-HELMET`).
        // What is fatal is the walk going quiet: this guard exists because the first version of the
        // block parser handed sequence items to the wrong owner and found 949 states instead of
        // 14000, and nothing in the output said so.
        int checked = known + dead.size();
        if (checked < FLOOR) {
            System.out.printf("CHECKED ONLY %d OF THE EXPECTED %d+ — the walk is broken%n", checked, FLOOR);
            System.exit(1);
        }
    }

    /** Whether the component has layers here or anywhere up the chain. */
    private static boolean hasLayers(
        Map<String, List<Declaration>> byId,
        Declaration declaration,
        String component,
        Set<String> visited
    ) {
        Block components = declaration.block.child("components");
        if (components != null) {
            for (Block item : components.children) {
                if (item.key != null) continue;
                if (!component.equals(item.scalar("type"))) continue;
                if (item.child("layers") != null) return true;
            }
        }
        for (String parent : declaration.parents) {
            if (parent == null || !visited.add(parent)) continue;
            for (Declaration candidate : byId.getOrDefault(parent, List.of())) {
                if (hasLayers(byId, candidate, component, visited)) return true;
            }
        }
        return false;
    }

    /** Below this the walk has stopped seeing the content rather than the content having changed. */
    private static final int FLOOR = 10000;

    private static String preview(List<String> declared) {
        List<String> head = declared.size() <= 5 ? declared : declared.subList(0, 5);
        return String.join(", ", head) + (declared.size() > 5 ? ", …" : "");
    }

    /** Every {@code state:} under a component, paired with the mapping that owns it. */
    private static void visit(Block node, Block component, List<Block[]> into) {
        for (Block child : node.children) {
            if (child.key == null) {
                visit(child, component, into);
                continue;
            }
            if (child.key.equals("state") && child.value != null && !child.value.isEmpty()) {
                into.add(new Block[] {node, child});
            }
            visit(child, component, into);
        }
    }

    private static void collectParents(Block declaration, List<String> into) {
        Block parent = declaration.child("parent");
        if (parent == null) return;
        if (parent.value != null) {
            String value = parent.value.trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                for (String piece : value.substring(1, value.length() - 1).split(",")) {
                    String id = piece.trim().replace("\"", "");
                    if (!id.isEmpty()) into.add(id);
                }
            } else if (!value.isEmpty()) {
                into.add(value);
            }
            return;
        }
        for (Block item : parent.children) {
            if (item.key == null && item.children.size() == 1 && item.children.get(0).key == null) {
                into.add(item.children.get(0).value);
            } else if (item.key == null && item.value != null) {
                into.add(item.value);
            }
        }
    }

    /** The sprite of the same component somewhere up the chain, first one found wins. */
    private static String ancestorSprite(
        Map<String, List<Declaration>> byId,
        Declaration declaration,
        String component,
        Set<String> visited
    ) {
        for (String parent : declaration.parents) {
            if (parent == null || !visited.add(parent)) continue;
            for (Declaration candidate : byId.getOrDefault(parent, List.of())) {
                Block components = candidate.block.child("components");
                if (components != null) {
                    for (Block item : components.children) {
                        if (item.key != null) continue;
                        if (!component.equals(item.scalar("type"))) continue;
                        String sprite = item.scalar("sprite");
                        if (sprite != null) return sprite;
                    }
                }
                String up = ancestorSprite(byId, candidate, component, visited);
                if (up != null) return up;
            }
        }
        return null;
    }

    /** A leading slash counts from a resource root, anything else from {@code Textures}. */
    private static Path resolve(List<Path> roots, String value) {
        String path = value.trim();
        if (path.isEmpty() || path.startsWith("!") || path.contains("$")) return null;
        for (Path root : roots) {
            Path candidate = path.startsWith("/")
                ? root.resolve(path.substring(1))
                : root.resolve("Textures").resolve(path);
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    private MeasureStates() {
    }
}
