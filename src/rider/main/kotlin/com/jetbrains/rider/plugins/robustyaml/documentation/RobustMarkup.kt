package com.jetbrains.rider.plugins.robustyaml.documentation

import com.intellij.openapi.util.text.StringUtil

/**
 * Robust rich text turned into HTML. Names and descriptions carry markup — 96 lines of YAML and 106
 * of `.ftl` in the content — and printed as written they read `[color=#9b59b6]Mansus Codex[/color]`
 * where the game shows a coloured name.
 *
 * Only the tags the client registers are rendered (`MarkupTagManager`: `color`, `bold`, `italic`,
 * `bolditalic`, `head`, `bullet`, and `mono` from the content). Anything else is text and stays text:
 * `[redacted]` and `[folded]` occur in descriptions and are registered nowhere, so a rule of "square
 * brackets are a tag" would eat prose. Tags left open at the end are closed here, which the renderer
 * also does — an unclosed `[color]` must not bleed into the rest of the popup.
 */
object RobustMarkup {
    fun toHtml(text: String): String {
        val html = StringBuilder()
        val open = ArrayDeque<String>()
        var at = 0

        while (at < text.length) {
            val start = text.indexOf('[', at)
            if (start < 0) {
                html.append(escape(text.substring(at)))
                break
            }
            html.append(escape(text.substring(at, start)))

            val end = text.indexOf(']', start)
            if (end < 0) {
                html.append(escape(text.substring(start)))
                break
            }

            val literal = text.substring(start, end + 1)
            val body = text.substring(start + 1, end)
            val closing = body.startsWith('/')
            val name = (if (closing) body.substring(1) else body.substringBefore('=')).trim().lowercase()
            val value = if (closing) null else body.substringAfter('=', "").trim()

            when {
                closing && open.lastOrNull() == name -> {
                    open.removeLast()
                    html.append(CLOSING.getValue(name))
                }
                closing -> html.append(escape(literal))
                name == BULLET -> html.append("• ")
                name in CLOSING && (value.isNullOrEmpty() || name == COLOR && isColor(value)) -> {
                    open.addLast(name)
                    html.append(if (name == COLOR) "<span style='color:$value'>" else OPENING.getValue(name))
                }
                else -> html.append(escape(literal))
            }
            at = end + 1
        }

        while (open.isNotEmpty()) html.append(CLOSING.getValue(open.removeLast()))
        return html.toString()
    }

    /** A hex value or one of the 145 names of `Color.DefaultColors`, which CSS knows by the same names. */
    private fun isColor(value: String): Boolean =
        value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '#' }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    private const val COLOR = "color"
    private const val BULLET = "bullet"

    private val OPENING = mapOf(
        "bold" to "<b>",
        "head" to "<b>",
        "italic" to "<i>",
        "bolditalic" to "<b><i>",
        "mono" to "<code>",
        COLOR to "<span>",
    )

    private val CLOSING = mapOf(
        "bold" to "</b>",
        "head" to "</b>",
        "italic" to "</i>",
        "bolditalic" to "</i></b>",
        "mono" to "</code>",
        COLOR to "</span>",
    )
}
