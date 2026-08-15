import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost of Find Usages. The index narrows a search to the files that mention the text; every one of
 * them is then parsed and its references resolved, so the number of candidates for a popular id is
 * what the feature actually costs. Run through {@code RobustYamlValueIndex.values}, the shipped
 * indexer, rather than a retelling of it.
 */
public final class MeasureUsages {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Class<?> index = MeasureReferences.pluginClass("RobustYamlValueIndex$Companion");
        Object instance = MeasureReferences.pluginClass("RobustYamlValueIndex")
            .getField("Companion").get(null);
        Method values = index.getMethod("values", CharSequence.class);

        Map<String, Integer> files = new HashMap<>();
        int mentions = 0;
        int scanned = 0;
        long started = System.currentTimeMillis();

        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            scanned++;
            @SuppressWarnings("unchecked")
            Map<String, ?> keys = (Map<String, ?>) values.invoke(instance, text);
            for (String key : keys.keySet()) {
                files.merge(key, 1, Integer::sum);
                mentions++;
            }
        }

        List<Map.Entry<String, Integer>> top = new ArrayList<>(files.entrySet());
        top.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

        int[] counts = files.values().stream().mapToInt(Integer::intValue).sorted().toArray();
        int median = counts.length == 0 ? 0 : counts[counts.length / 2];
        long alone = files.values().stream().filter(it -> it == 1).count();

        System.out.println("files scanned: " + scanned + " in " + (System.currentTimeMillis() - started) + " ms");
        System.out.println("distinct values: " + files.size() + ", file-value pairs: " + mentions);
        System.out.println("median files per value: " + median
            + ", mentioned in a single file: " + alone
            + " (" + (100 * alone / Math.max(1, files.size())) + "%)");
        System.out.println("widest values (files to open on Alt+F7):");
        for (Map.Entry<String, Integer> entry : top.subList(0, Math.min(top.size(), 10))) {
            System.out.println("   " + entry.getKey() + ": " + entry.getValue());
        }
    }
}
