package com.jetbrains.rider.plugins.robustyaml.index

/** What a string literal of a `.cs` file names in the content. */
enum class CodeLinkKind { PATH, PROTOTYPE_ID, SPRITE_STATE }

/**
 * A literal that names something the plugin knows, and where in the file it sits.
 *
 * [prototypeClass] is the type argument of `ProtoId<X>`, which decides the kind the id has to be
 * declared under; `EntProtoId` leaves it null because it is always an entity — the parameter of its
 * generic form constrains a component, not a kind. [spritePath] is the `.rsi` a state belongs to,
 * the way `sprite:` beside `state:` is in YAML. Each kind fills one of them and neither the other.
 */
data class CodeLink(
    val kind: CodeLinkKind,
    val value: String,
    val prototypeClass: String?,
    val spritePath: String?,
    val start: Int,
    val end: Int,
)

/**
 * String literals of C# that point at the content: a path under `Resources` and an id of a prototype.
 * Both are read from the text, because the frontend has no parser for C# — the analysis lives in the
 * backend behind rd, and a literal is where every one of these references is written.
 *
 * The scanner is the one [RobustLocaleUsageIndex] already walks the same files with, so a comment is
 * stepped over rather than stripped: `// spawns "MobHuman"` is prose, and a rename that rewrote it
 * would be editing English.
 */
object RobustCodeLinks {
    const val EXTENSION = "cs"

    /**
     * The type is the only evidence there is. `"MobHuman"` is a string like any other, and what makes
     * it an id is `EntProtoId` or `ProtoId<X>` written beside it; measured on ss14-wega, 1117 of the
     * 1123 literals declared that way stand on the line their type is on.
     */
    private val TYPED_ID = Regex("""\bEntProtoId\b|\bProtoId\s*<\s*(\w+)\s*>""")

    /**
     * An id as the content spells it. Lower case has to be allowed — `ProtoId<ShaderPrototype>
     * UnshadedShader = "unshaded"` — and a dash must not be: a dashed literal is a localization key,
     * and that one is [RobustLocaleUsageIndex]'s to claim.
     */
    private val PROTOTYPE_ID = Regex("""[A-Za-z][A-Za-z0-9_]*""")

    /**
     * The last line a declaration may be broken over: `= new() {` and the literals on the line under
     * it. Only one line back, and only when that line both names the type and ends open — six
     * literals of ss14-wega are written that way, all of them items of a list of `EntProtoId`.
     */
    private val CONTINUES = setOf('=', '{', '[')

    /**
     * A state of an `.rsi`, which is a file name and may hold a dash: `equipped-INNERCLOTHING`.
     * Loose on purpose — what makes the literal a state is the path standing before it, not its
     * shape, and the frame either exists in that directory or the jump does not happen.
     */
    private val STATE = Regex("""[A-Za-z][\w-]*""")

    private const val RSI_SUFFIX = ".rsi"

    /** What may stand between the two arguments of `new SpriteSpecifier.Rsi(new ResPath(…), "state")`. */
    private val BETWEEN_ARGUMENTS = setOf('"', ')', ',')

    fun links(text: CharSequence): List<CodeLink> {
        val literals = RobustLocaleUsageIndex.stringRanges(text)
        val found = mutableListOf<CodeLink>()
        for ((at, range) in literals.withIndex()) {
            val (start, end) = range
            val value = text.subSequence(start, end).toString()
            val rsi = enclosingRsi(text, literals, at)
            when {
                // The state is asked first, because there the `.rsi` beside it says what the
                // literal is — the way `sprite:` says it in YAML — and a state may be spelled with
                // a dash (`equipped-INNERCLOTHING`), which no other rule here allows.
                rsi != null && STATE.matches(value) ->
                    found += CodeLink(CodeLinkKind.SPRITE_STATE, value, null, rsi, start, end)

                looksLikePath(value) ->
                    found += CodeLink(CodeLinkKind.PATH, value, null, null, start, end)

                PROTOTYPE_ID.matches(value) -> {
                    val declaring = declaringType(text, start) ?: continue
                    found += CodeLink(
                        CodeLinkKind.PROTOTYPE_ID,
                        value,
                        declaring.groupValues[1].takeIf { it.isNotEmpty() },
                        null,
                        start,
                        end,
                    )
                }
            }
        }
        return found
    }

    /** Ids alone — the half a reverse search needs, because a path is never renamed from YAML. */
    fun ids(text: CharSequence): List<CodeLink> =
        links(text).filter { it.kind == CodeLinkKind.PROTOTYPE_ID }

    /**
     * The link the caret stands in. Both ends count as inside: the caret sits between characters,
     * and clicking right after the last one of a value still points at it.
     */
    fun at(links: List<CodeLink>, offset: Int): CodeLink? =
        links.firstOrNull { offset >= it.start && offset <= it.end }

    /** [at] over a file read from scratch — the shape a test can check without a project. */
    fun linkAt(text: CharSequence, offset: Int): CodeLink? = at(links(text), offset)

    /**
     * A path is absolute, and that is what tells it from a name: the engine reads a leading `/` from
     * the root of the resources, and 993 literals of ss14-wega are written that way. Whether the file
     * is there is not asked here — an index answers about one file at a time — so the check is only
     * that the text is shaped like a path at all; a value that resolves to nothing simply offers no
     * jump.
     */
    private fun looksLikePath(value: String): Boolean =
        value.length > 1 &&
            value.startsWith('/') &&
            value.none { it.isWhitespace() || it == '\\' } &&
            (value.indexOf('/', 1) > 0 || '.' in value)

    /**
     * The type written before the literal, on its line or on the one above it. Searched backwards
     * from the literal rather than forwards from the type, because one declaration may carry several
     * of them — `new ProtoId<RoleTypePrototype>[] { "SoloAntagonist", "TeamAntagonist" }` — and each
     * of them is an id.
     */
    private fun declaringType(text: CharSequence, literal: Int): MatchResult? {
        val lineStart = lineStartAt(text, literal)
        TYPED_ID.findAll(text.subSequence(lineStart, literal)).lastOrNull()?.let { return it }

        val previous = previousLine(text, lineStart) ?: return null
        val open = text.subSequence(previous.first, previous.last).trimEnd()
        if (open.isEmpty() || open.last() !in CONTINUES) return null
        return TYPED_ID.findAll(open).lastOrNull()
    }

    /**
     * The `.rsi` a literal is a state of: the argument before it, with nothing between the two but
     * the syntax of the call. `new SpriteSpecifier.Rsi(new ResPath("…job_icons.rsi"), "Syndicate")`
     * is the shape, and 62 literals of ss14-wega in 13 files are written that way — often with the
     * state on the next line, which is why the gap is read by its characters and not by the line.
     */
    private fun enclosingRsi(text: CharSequence, literals: List<Pair<Int, Int>>, at: Int): String? {
        if (at == 0) return null
        val (start, end) = literals[at - 1]
        val path = text.subSequence(start, end).toString()
        if (!path.endsWith(RSI_SUFFIX, ignoreCase = true) || !looksLikePath(path)) return null

        val gap = text.subSequence(end, literals[at].first)
        if (gap.none { it == ',' }) return null
        return path.takeIf { gap.all { c -> c.isWhitespace() || c in BETWEEN_ARGUMENTS } }
    }

    private fun lineStartAt(text: CharSequence, offset: Int): Int {
        var at = offset
        while (at > 0 && text[at - 1] != '\n') at--
        return at
    }

    /** The last non-empty line before [lineStart], as a half-open range without its line break. */
    private fun previousLine(text: CharSequence, lineStart: Int): IntRange? {
        var end = lineStart - 1
        while (end > 0) {
            val start = lineStartAt(text, end)
            if (text.subSequence(start, end).isNotBlank()) return start..end
            end = start - 1
        }
        return null
    }
}
