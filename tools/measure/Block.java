import java.util.ArrayList;
import java.util.List;

/**
 * A block-YAML tree, just enough of one to answer "which mapping owns this key".
 *
 * <p>Line regexes were tried first and are the reason this exists. A `state:` belongs to its layer,
 * a layer to its component and a component to its declaration, and the plugin answers that by
 * walking PSI; a measurement that answers it by counting spaces disagrees with the plugin exactly
 * where the content is unusual — which is where the interesting cases live. The same mistake has
 * already been paid for once, in {@code MeasureFlags}, where a block sequence written at the level
 * of its key was read as three values instead of twenty-nine.
 *
 * <p>Only what prototypes actually contain is parsed: block mappings, block sequences, scalars,
 * and a dash carrying the first key of its item. Flow collections are kept as opaque text — no
 * `sprite:` or `state:` in the checkout is written inside one.
 */
final class Block {
    /** The key this node hangs on, or null for an item of a sequence. */
    final String key;
    /** The scalar written after the colon, or null when the value is a collection. */
    final String value;
    final int line;
    final List<Block> children = new ArrayList<>();

    private Block(String key, String value, int line) {
        this.key = key;
        this.value = value;
        this.line = line;
    }

    static Block parse(String text) {
        Block root = new Block(null, null, 0);
        List<Block> stack = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        stack.add(root);
        indents.add(-1);

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].replace("\r", "");
            String stripped = strip(raw);
            if (stripped.isBlank() || stripped.trim().equals("---")) continue;

            int indent = 0;
            while (indent < stripped.length() && (stripped.charAt(indent) == ' ' || stripped.charAt(indent) == '\t')) {
                indent++;
            }
            String content = stripped.substring(indent).trim();
            if (content.isEmpty()) continue;

            // A dash opens an item; anything written after it starts inside that item, two columns
            // further in as far as ownership goes.
            boolean item = content.startsWith("-") && (content.length() == 1 || content.charAt(1) == ' ');
            String rest = item ? content.substring(1).trim() : content;

            // An item may stand at the indent of the key that owns it, and SS14 writes it that way:
            // `components:` and its `- type:` share a column. Popping on `<=` like an ordinary key
            // hands the item to the declaration instead of to `components:`, and the count of
            // `state:` came out as 949 of 18479 — the same mistake `MeasureFlags` once made.
            while (indents.size() > 1) {
                int top = indents.get(indents.size() - 1);
                Block node = stack.get(stack.size() - 1);
                boolean owner = item && indent == top && node.key != null && node.value == null;
                if (indent > top || owner) break;
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            Block parent = stack.get(stack.size() - 1);

            Block node;
            if (item) {
                node = new Block(null, null, i + 1);
                parent.children.add(node);
                parent = node;
                indents.add(indent);
                stack.add(node);
                if (rest.isEmpty()) continue;
                indent = indent + 2;
            }

            int colon = colonOf(rest);
            if (colon < 0) {
                parent.children.add(new Block(null, rest, i + 1));
                continue;
            }
            String key = rest.substring(0, colon).trim();
            String value = rest.substring(colon + 1).trim();
            node = new Block(unquote(key), value.isEmpty() ? null : unquote(value), i + 1);
            parent.children.add(node);
            indents.add(indent);
            stack.add(node);
        }
        return root;
    }

    /** The colon that separates a key from its value: one followed by a blank or ending the line. */
    private static int colonOf(String text) {
        boolean single = false;
        boolean doubled = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !doubled) single = !single;
            else if (c == '"' && !single) doubled = !doubled;
            else if (c == ':' && !single && !doubled
                && (i + 1 == text.length() || text.charAt(i + 1) == ' ' || text.charAt(i + 1) == '\t')) {
                return i;
            }
        }
        return -1;
    }

    /** Comments cut, but not a `#` inside quotes — colours are written `"#ff0000"`. */
    private static String strip(String line) {
        boolean single = false;
        boolean doubled = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !doubled) single = !single;
            else if (c == '"' && !single) doubled = !doubled;
            else if (c == '#' && !single && !doubled && (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    Block child(String name) {
        for (Block child : children) {
            if (name.equals(child.key)) return child;
        }
        return null;
    }

    String scalar(String name) {
        Block child = child(name);
        return child == null ? null : child.value;
    }

    /** Every node under this one, the node itself included, in document order. */
    void walk(List<Block> into) {
        into.add(this);
        for (Block child : children) child.walk(into);
    }
}
