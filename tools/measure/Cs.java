import java.util.ArrayList;
import java.util.List;

/**
 * Minimal C# lexing shared by the measurements.
 *
 * <p>Attributes cannot be read with a backwards search for the previous '}' or ';': both appear
 * inside attribute arguments ({@code [Access(new[] { typeof(X) })]}) and inside comments
 * ({@code // ... need Solution data;}). Comments and literals are blanked out first, and only then
 * the attribute list before a declaration is collected with bracket balancing.
 */
final class Cs {
    private static final int CODE = 0;
    private static final int LINE_COMMENT = 1;
    private static final int BLOCK_COMMENT = 2;
    private static final int STRING = 3;
    private static final int CHAR = 4;
    private static final int VERBATIM = 5;

    private Cs() {
    }

    /** Replaces comment and literal content with spaces, keeping every offset intact. */
    static String blankCommentsAndLiterals(String text) {
        char[] out = text.toCharArray();
        int state = CODE;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
            switch (state) {
                case CODE:
                    if (current == '/' && next == '/') {
                        state = LINE_COMMENT;
                        out[i] = ' ';
                        out[++i] = ' ';
                    } else if (current == '/' && next == '*') {
                        state = BLOCK_COMMENT;
                        out[i] = ' ';
                        out[++i] = ' ';
                    } else if (current == '@' && next == '"') {
                        state = VERBATIM;
                        i++;
                    } else if (current == '"') {
                        state = STRING;
                    } else if (current == '\'') {
                        state = CHAR;
                    }
                    break;
                case LINE_COMMENT:
                    if (current == '\n') state = CODE;
                    else out[i] = ' ';
                    break;
                case BLOCK_COMMENT:
                    if (current == '*' && next == '/') {
                        state = CODE;
                        out[i] = ' ';
                        out[++i] = ' ';
                    } else if (current != '\n') {
                        out[i] = ' ';
                    }
                    break;
                case STRING:
                    if (current == '\\') {
                        out[i] = ' ';
                        if (i + 1 < out.length) out[++i] = ' ';
                    } else if (current == '"') {
                        state = CODE;
                    } else {
                        out[i] = ' ';
                    }
                    break;
                case CHAR:
                    if (current == '\\') {
                        out[i] = ' ';
                        if (i + 1 < out.length) out[++i] = ' ';
                    } else if (current == '\'') {
                        state = CODE;
                    } else {
                        out[i] = ' ';
                    }
                    break;
                case VERBATIM:
                    if (current == '"') {
                        if (next == '"') {
                            out[i] = ' ';
                            out[++i] = ' ';
                        } else {
                            state = CODE;
                        }
                    } else {
                        out[i] = ' ';
                    }
                    break;
                default:
                    break;
            }
        }
        return new String(out);
    }

    /**
     * Text of the attribute lists immediately preceding {@code declaration}, in a source that has
     * already been through {@link #blankCommentsAndLiterals}.
     */
    static String attributesBefore(String blanked, int declaration) {
        List<String> blocks = new ArrayList<>();
        int i = declaration - 1;
        while (true) {
            while (i >= 0 && Character.isWhitespace(blanked.charAt(i))) i--;
            if (i < 0 || blanked.charAt(i) != ']') break;

            int depth = 0;
            int end = i;
            while (i >= 0) {
                char c = blanked.charAt(i);
                if (c == ']') depth++;
                else if (c == '[') {
                    depth--;
                    if (depth == 0) break;
                }
                i--;
            }
            if (i < 0) break;
            blocks.add(blanked.substring(i, end + 1));
            i--;
        }
        return String.join(" ", blocks);
    }
}
