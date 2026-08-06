package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object RobustXmlDoc {
    private val DECLARATION = Regex(
        """(?:(?:public|internal|private|protected|sealed|partial|abstract|static|readonly|record|unsafe)\s+)+""" +
            """(?:class|record|struct)\s+(\w+)""",
    )
    private val SUMMARY = Regex("""<summary>(.*?)</summary>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""<(/?)(\w+)\b([^>]*?)/?>""")
    private val ATTRIBUTE_VALUE = Regex("""(?:cref|langword|name)\s*=\s*"([^"]*)"""")
    private val SPACES = Regex("""\s+""")

    data class Extracted(val className: String, val summary: String?)

    fun extract(file: VirtualFile, candidates: List<String>): Extracted? {
        val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return null
        return extract(text, candidates)
    }

    fun extract(text: CharSequence, candidates: List<String>): Extracted? {
        val declarations = DECLARATION.findAll(text).toList()
        val declaration = candidates.firstNotNullOfOrNull { name ->
            declarations.firstOrNull { it.groupValues[1] == name }
        } ?: return null

        return Extracted(declaration.groupValues[1], summaryAt(text, declaration.range.first))
    }

    fun summaryAt(text: CharSequence, offset: Int): String? {
        val doc = docAbove(text.subSequence(0, offset)) ?: return null
        val summary = SUMMARY.find(doc)?.groupValues?.get(1) ?: return null
        return html(summary)
    }

    fun html(summary: String): String? = toHtml(summary).takeIf { it.isNotEmpty() }

    private fun docAbove(prefix: CharSequence): String? {
        val lines = prefix.toString().lines()
        val doc = mutableListOf<String>()
        var index = lines.size - 2
        while (index >= 0) {
            val line = lines[index].trim()
            when {
                line.startsWith("///") -> doc += line.removePrefix("///").trim()
                line.isEmpty() || line.startsWith("[") || line.endsWith("]") || line.startsWith("#") -> Unit
                else -> break
            }
            index--
        }
        return doc.asReversed().joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun toHtml(summary: String): String {
        val html = StringBuilder()
        var last = 0
        for (tag in TAG.findAll(summary)) {
            html.append(StringUtil.escapeXmlEntities(summary.substring(last, tag.range.first)))
            last = tag.range.last + 1
            val closing = tag.groupValues[1] == "/"
            when (tag.groupValues[2]) {
                "see", "seealso", "paramref", "typeparamref" -> {
                    val target = ATTRIBUTE_VALUE.find(tag.groupValues[3])?.groupValues?.get(1) ?: continue
                    html.append("<code>")
                        .append(StringUtil.escapeXmlEntities(target.substringAfterLast('.')))
                        .append("</code>")
                }
                "c" -> html.append(if (closing) "</code>" else "<code>")
                "para" -> if (!closing) html.append("<p>")
                "br" -> html.append("<br>")
            }
        }
        html.append(StringUtil.escapeXmlEntities(summary.substring(last)))
        return SPACES.replace(html, " ").trim()
    }
}
