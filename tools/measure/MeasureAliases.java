import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the id index makes of prototypes that name themselves through a YAML alias.
 *
 * <p>Robust resolves anchors and aliases while parsing the document, so {@code id: *BackgammonBoard}
 * reaches the engine as the text written next to {@code &BackgammonBoard} and nothing downstream can
 * tell the two apart. The index reads text, so it used to take the alias for the id itself: the real
 * id went missing and a key spelled with a star took its place.
 *
 * <p>Two things are guarded, both with a non-zero exit. No key may start with a star — that is the
 * junk the old reading produced, and it showed up in completion and in Goto Symbol. And every id
 * declared through an alias has to come back out of the index, or the false "unknown prototype" this
 * was written for is still there.
 */
public final class MeasureAliases {
    /** The whole value is an alias: `id: *BackgammonBoard`. */
    private static final Pattern ALIAS =
        Pattern.compile("(?m)^[ \\t]*(?:-[ \\t]+)?([\\w.-]+)[ \\t]*:[ \\t]*\\*([\\w-]+)[ \\t]*(?:#.*)?$");

    /** A single-token value carrying an anchor, the only shape an id can be written in. */
    private static final Pattern ANCHOR =
        Pattern.compile("(?m)^[ \\t]*(?:-[ \\t]+)?(?:[\\w.-]+[ \\t]*:[ \\t]*)?&([\\w-]+)[ \\t]+"
            + "\"?([^\\s\"#]+)\"?[ \\t]*(?:#.*)?$");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Object ids = MeasureReferences.companion("RobustPrototypeIdIndex");
        Method prototypeIds = ids.getClass().getMethod("prototypeIds", CharSequence.class);

        List<Path> prototypes = MeasureHoles.prototypes(root);

        Map<String, Integer> aliasKeys = new TreeMap<>();
        int aliasCount = 0;
        int scalarAnchors = 0;
        List<String> expected = new ArrayList<>();
        Set<String> indexed = new HashSet<>();
        List<String> starred = new ArrayList<>();

        long start = System.currentTimeMillis();
        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            Map<String, String> found = (Map<String, String>) prototypeIds.invoke(ids, text);
            indexed.addAll(found.keySet());
            for (String id : found.keySet()) {
                if (id.startsWith("*")) starred.add(file + ": " + id);
            }

            Map<String, String> anchors = new TreeMap<>();
            Matcher anchor = ANCHOR.matcher(text);
            while (anchor.find()) {
                anchors.put(anchor.group(1), anchor.group(2));
                scalarAnchors++;
            }

            Matcher alias = ALIAS.matcher(text);
            while (alias.find()) {
                aliasCount++;
                aliasKeys.merge(alias.group(1), 1, Integer::sum);
                if (!"id".equals(alias.group(1))) continue;

                String value = anchors.get(alias.group(2));
                if (value == null) continue;
                expected.add(value);
                if (!found.containsKey(value)) {
                    System.out.println("LOST: " + file + ": id: *" + alias.group(2) + " -> " + value);
                }
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("prototype files: " + prototypes.size());
        System.out.println("aliases taking a whole value: " + aliasCount
            + " | anchors on a single-token value: " + scalarAnchors);
        System.out.println("keys the aliases stand under: " + aliasKeys);
        System.out.println("ids declared through an alias: " + expected.size() + " " + expected);
        System.out.println("ids in the index: " + indexed.size() + " | full pass " + elapsed + " ms");

        int missing = 0;
        for (String id : expected) {
            if (!indexed.contains(id)) missing++;
        }
        System.out.println("MISSING: " + missing);
        System.out.println("STARRED: " + starred.size() + (starred.isEmpty() ? "" : " " + starred));

        if (missing > 0 || !starred.isEmpty()) System.exit(1);
    }
}
