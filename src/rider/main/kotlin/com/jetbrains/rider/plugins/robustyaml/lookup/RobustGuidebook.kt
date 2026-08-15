package com.jetbrains.rider.plugins.robustyaml.lookup

import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/**
 * The guidebook — XML documents under `Resources/ServerInfo` that the in-game guide renders. Every
 * embed tag names a prototype by id: 1193 entities, 42 reagents and 5 technology disciplines in
 * ss14-wega, 726 of them distinct. Nothing checks those ids at load time, so a page whose entity was
 * deleted throws on open — `GuideEntityEmbed.TryParseTag` goes straight to
 * `IEntityManager.SpawnEntity`.
 *
 * Unlike a `.ftl` or a C# literal, an XML document has a parser on the frontend, so a mention here
 * is an ordinary [com.intellij.psi.PsiReference] on the attribute value rather than a range carried
 * by hand.
 */
object RobustGuidebook {
    const val EXTENSION = "xml"

    private const val TAG_PREFIX = "Guide"

    /** Embed attributes that name a prototype, and the kind of prototype each of them names. */
    private val ATTRIBUTES = mapOf(
        "Entity" to "entity",
        "Reagent" to "reagent",
        "Discipline" to "techDiscipline",
    )

    /**
     * The tag is part of the rule on purpose. `Entity="…"` on its own reads like an attribute any
     * XML of the checkout may carry, and the index would grow by every value that happens to be
     * spelled like an id; every one of the 1240 embeds in the content is written on a `Guide*` tag.
     */
    private val TAG = Regex("""<$TAG_PREFIX\w*""")

    /**
     * One attribute, matched where the previous one ended. Reading them one by one is what keeps
     * this rule and [kindOf] saying the same thing about the same document: XML allows either quote
     * around a value, any number of attributes on a tag and a bare `>` inside a value, and a single
     * pattern reaching from the tag head to the attribute (`<Guide\w*\s[^>]*?Entity="…"`) missed all
     * three. The cost of missing one is one-sided — PSI still resolves the attribute and Ctrl+click
     * still works, while the file quietly drops out of the candidate list of Find Usages and rename
     * passes the embed by. None of the three forms occurs in ss14-wega today, which is the reason to
     * fix it now: there is nothing to measure against later.
     */
    private val ATTRIBUTE = Regex("""\s+([A-Za-z_][\w.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    /** What an id may look like; anything else the embed names is not one and stays out. */
    private val ID = Regex("""[A-Za-z][\w.]*""")

    /** An id an embed names, and the kind of prototype it is expected to be. */
    data class Reference(val id: String, val kind: String, val start: Int)

    /**
     * What the text names, read without PSI — the shape both the index and the measurement use.
     * Markup commented out is not stripped: a wrong candidate costs a file the walk opens for
     * nothing, and what the file really holds is decided afterwards by PSI, where a comment carries
     * no attribute at all.
     */
    fun references(text: CharSequence): List<Reference> {
        val references = mutableListOf<Reference>()
        for (tag in TAG.findAll(text)) {
            var at = tag.range.last + 1
            while (true) {
                // The walk ends by itself at `>` or `/>`: neither begins an attribute.
                val attribute = ATTRIBUTE.matchAt(text, at) ?: break
                at = attribute.range.last + 1

                val kind = ATTRIBUTES[attribute.groupValues[1]] ?: continue
                val id = attribute.groups[2] ?: attribute.groups[3] ?: continue
                if (!ID.matches(id.value)) continue
                references += Reference(id.value, kind, id.range.first)
            }
        }
        return references
    }

    /** Prototype ids the text names, for the index that narrows Find Usages to candidate files. */
    fun ids(text: CharSequence): Set<String> =
        references(text).mapTo(mutableSetOf()) { it.id }

    /** The kind of prototype [value] names, or null when it names none. */
    fun kindOf(value: XmlAttributeValue): String? {
        val attribute = value.parent as? XmlAttribute ?: return null
        if (attribute.valueElement !== value) return null
        val tag = attribute.parent ?: return null
        if (!tag.name.startsWith(TAG_PREFIX)) return null
        return ATTRIBUTES[attribute.name]
    }

    fun namesPrototype(value: XmlAttributeValue): Boolean = kindOf(value) != null
}
