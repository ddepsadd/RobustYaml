import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watchdog over the validation of `!type:` tags.
 *
 * <p>The tag is checked against the inheritors of the declared type of the field, and the answer
 * comes from the ReSharper backend, which cannot be run here. What can be checked offline is the
 * premise of that validation: every type named by a tag in the content is declared somewhere in the
 * checkout, and is not abstract. A tag that fails either test is a tag the annotator would paint
 * red — so a non-empty report means either dead content or a false positive waiting to happen.
 */
public final class MeasureTags {
    private static final Pattern TAG = Pattern.compile("!type:([\\w.]+)");

    private static final Pattern DECLARATION = Pattern.compile(
        "((?:(?:public|internal|private|protected|sealed|partial|abstract|static|readonly|record|unsafe)\\s+)+)"
            + "(?:class|record|struct)\\s+(\\w+)");

    /** Names {@code ReflectionManager.TryLooseGetType} answers before searching the assemblies. */
    private static final List<String> LOOSE_TYPES =
        List.of("Byte", "Bool", "Double", "SByte", "Single", "String");

    /**
     * A commented-out tag is not a reference: the annotator never sees it, because PSI has no
     * key-value there at all. Reading the file as plain text counted `#      operator:
     * !type:StashActiveHandOperator` as dead content and reported a finding that does not exist.
     */
    private static String uncommented(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int hash = line.indexOf('#');
            result.append(hash < 0 ? line : line.substring(0, hash)).append('\n');
        }
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);

        Map<String, Boolean> declared = new HashMap<>();
        for (Path file : MeasureHoles.sources(root, ".cs")) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            Matcher m = DECLARATION.matcher(Cs.blankCommentsAndLiterals(text));
            while (m.find()) {
                boolean isAbstract = m.group(1).contains("abstract");
                // A partial class may spell the modifier once; any concrete declaration wins.
                declared.merge(m.group(2), isAbstract, (a, b) -> a && b);
            }
        }

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> examples = new TreeMap<>();
        int values = 0;
        int files = 0;
        for (Path file : MeasureHoles.prototypes(root)) {
            String text = MeasureHoles.read(file);
            if (text == null) continue;

            Matcher m = TAG.matcher(uncommented(text));
            boolean tagged = false;
            while (m.find()) {
                String type = m.group(1);
                counts.merge(type, 1, Integer::sum);
                examples.putIfAbsent(type, root.relativize(file).toString());
                values++;
                tagged = true;
            }
            if (tagged) files++;
        }

        List<String> unknown = new ArrayList<>();
        List<String> abstracts = new ArrayList<>();
        int loose = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (LOOSE_TYPES.contains(entry.getKey())) {
                loose += entry.getValue();
                continue;
            }

            Boolean isAbstract = declared.get(entry.getKey());
            if (isAbstract == null) {
                unknown.add(entry.getKey() + " x" + entry.getValue() + "   (" + examples.get(entry.getKey()) + ")");
            } else if (isAbstract) {
                abstracts.add(entry.getKey() + " x" + entry.getValue() + "   (" + examples.get(entry.getKey()) + ")");
            }
        }

        System.out.println("classes declared in the checkout: " + declared.size());
        System.out.println("tagged values: " + values + " in " + files + " files, distinct types: " + counts.size());
        System.out.println("engine primitive aliases: " + loose + " values");
        System.out.println("types not declared anywhere: " + unknown.size());
        for (String line : unknown.subList(0, Math.min(unknown.size(), 10))) System.out.println("   " + line);
        System.out.println("types declared abstract: " + abstracts.size());
        for (String line : abstracts.subList(0, Math.min(abstracts.size(), 10))) System.out.println("   " + line);

        if (!unknown.isEmpty() || !abstracts.isEmpty()) {
            System.out.println();
            System.out.println("Each line above is a tag the annotator paints red. Two are known dead content on "
                + "ss14-wega; anything beyond that is a false positive and has to be checked by hand.");
        }
    }
}
