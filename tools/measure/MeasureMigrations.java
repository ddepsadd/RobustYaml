import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

        Object migrations = Class.forName("com.jetbrains.rider.plugins.robustyaml.RobustMigrations")
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

        if (dictionaryLines != entries.size() || unreachableTargets != 0) {
            System.out.println();
            System.out.println("Migration parsing regressed: either lines are dropped or a fix would "
                + "suggest a missing id.");
            System.exit(1);
        }
    }
}
