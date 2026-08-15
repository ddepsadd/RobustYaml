import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks that {@code migration.yml} still parses whole and that every rename target exists, so the
 * quick fix can never offer an id that is not declared anywhere.
 */
public final class MeasureMigrations {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Path migration = root.resolve("Resources").resolve("migration.yml");
        if (!Files.exists(migration)) {
            System.out.println("no migration.yml under " + root);
            return;
        }

        Object migrations = MeasureReferences.pluginClass("RobustMigrations")
            .getField("INSTANCE").get(null);
        Method parse = migrations.getClass().getMethod("parse", CharSequence.class);

        Object references = MeasureReferences.companion("RobustYamlReferenceIndex");
        Method referencesOf = references.getClass().getMethod("references", CharSequence.class);

        Object ids = MeasureReferences.companion("RobustPrototypeIdIndex");
        Method prototypeIds = ids.getClass().getMethod("prototypeIds", CharSequence.class);

        Map<String, String> entries = (Map<String, String>) parse.invoke(migrations, MeasureHoles.read(migration));
        long removals = entries.values().stream().filter(String::isEmpty).count();

        long dictionaryLines = Files.readAllLines(migration).stream()
            .filter(line -> line.matches("^[^#\\s]+[ \\t]*:.*"))
            .count();

        List<Path> prototypes = MeasureHoles.prototypes(root);
        Set<String> knownIds = new HashSet<>();
        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text != null) knownIds.addAll(((Map<String, String>) prototypeIds.invoke(ids, text)).keySet());
        }

        Map<String, String> fixable = new TreeMap<>();
        Map<String, Integer> deadWithoutEntry = new TreeMap<>();
        int staleButAlive = 0;
        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (String key : ((Map<String, Void>) referencesOf.invoke(references, text)).keySet()) {
                if (!key.startsWith("p:") && !key.startsWith("r:")) continue;
                String id = key.substring(2);
                if (knownIds.contains(id)) {
                    if (entries.containsKey(id)) staleButAlive++;
                } else if (entries.containsKey(id)) {
                    fixable.put(id, entries.get(id).isEmpty() ? "(removed)" : entries.get(id));
                } else {
                    deadWithoutEntry.merge(id, 1, Integer::sum);
                }
            }
        }

        long unreachableTargets = entries.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty() && !knownIds.contains(e.getValue()))
            .count();

        System.out.println("dictionary lines in file: " + dictionaryLines + ", parsed entries: " + entries.size()
            + " (renames " + (entries.size() - removals) + ", removals " + removals + ")");
        System.out.println("dead refs fixable by migration: " + fixable);
        System.out.println("dead refs without a migration entry: " + deadWithoutEntry);
        System.out.println("live refs that migration also renames: " + staleButAlive);
        System.out.println("rename targets that do not exist: " + unreachableTargets);

        probeTypos(prototypes, prototypeIds, ids);

        if (dictionaryLines != entries.size() || unreachableTargets != 0) {
            System.out.println();
            System.out.println("Migration parsing regressed: either lines are dropped or a fix would "
                + "suggest a missing id.");
            System.exit(1);
        }
    }

    /**
     * What the quick fix costs and what it still finds. A dead id of the content usually has no near
     * neighbour at all — the one dead reference in ss14-wega is fixed by the migration dictionary,
     * not by distance — so the suggestions are probed with typos made on purpose: every 200th id of
     * the largest kind with one letter swapped has to come back.
     *
     * The timing is the point of the guard as much as the recovery is. Suggestions are built while
     * the problem is reported, that is on every pass of the daemon, and the first version searched
     * all 27421 ids of the checkout instead of the 14083 of the kind at hand.
     */
    @SuppressWarnings("unchecked")
    private static void probeTypos(List<Path> prototypes, Method prototypeIds, Object ids) throws Exception {
        Map<String, List<String>> byKind = new TreeMap<>();
        for (Path file : prototypes) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;
            for (Map.Entry<String, String> entry :
                ((Map<String, String>) prototypeIds.invoke(ids, text)).entrySet()) {
                for (String site : entry.getValue().split(";")) {
                    String kind = site.substring(0, Math.max(site.indexOf('@'), 0));
                    if (!kind.isEmpty()) byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(entry.getKey());
                }
            }
        }

        String widest = byKind.entrySet().stream()
            .max(Comparator.comparingInt(e -> e.getValue().size()))
            .map(Map.Entry::getKey).orElse(null);
        if (widest == null) return;

        List<String> pool = byKind.get(widest);
        Object companion = MeasureReferences.pluginClass("ChangePrototypeIdFix")
            .getField("Companion").get(null);
        Method suggest = companion.getClass().getMethod("suggest", List.class, String.class);

        int probes = 0;
        int recovered = 0;
        long worst = 0;
        long spent = 0;
        for (int i = 0; i < pool.size(); i += 200) {
            String id = pool.get(i);
            if (id.length() < 6) continue;
            String typo = id.substring(0, id.length() / 2) + "x" + id.substring(id.length() / 2 + 1);

            long at = System.nanoTime();
            List<String> suggestions = (List<String>) suggest.invoke(companion, pool, typo);
            long took = System.nanoTime() - at;
            spent += took;
            worst = Math.max(worst, took);

            probes++;
            if (suggestions.contains(id)) recovered++;
        }
        System.out.println("typo probes over " + pool.size() + " '" + widest + "' ids: " + probes
            + ", suggested back: " + recovered
            + ", slowest call: " + worst / 1_000_000 + " ms, average: "
            + (probes == 0 ? 0 : spent / probes / 1_000_000) + " ms");
    }
}
