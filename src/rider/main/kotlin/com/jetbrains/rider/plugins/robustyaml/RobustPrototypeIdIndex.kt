package com.jetbrains.rider.plugins.robustyaml

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class RobustPrototypeIdIndex : FileBasedIndexExtension<String, String>() {
    override fun getName(): ID<String, String> = NAME

    override fun getVersion(): Int = 4

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension.equals("yml", ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, String, FileContent> =
        DataIndexer { content -> prototypeIds(content.contentAsText) }

    companion object {
        val NAME: ID<String, String> = ID.create("robustyaml.prototype.ids")

        private const val ENTRY_SEPARATOR = ';'
        private const val KIND_SEPARATOR = '@'

        private const val ALIAS_PREFIX = '*'

        private val DECLARATION =
            Regex(
                """(?m)^﻿?(?:-[ \t]+type[ \t]*:[ \t]*(\w+)""" +
                    """|[ ]{0,2}id[ \t]*:[ \t]*"?([^\s"#]+)"?)[ \t]*(?:#.*)?\r?$""",
            )

        /**
         * A single-token value marked by an anchor. Nothing longer is read: `offset: &icon-offset
         * -0.09375, 0.0625` marks a vector, and an id never carries a space, so a partial capture
         * would only put junk under a key nobody looks up.
         */
        private val ANCHOR =
            Regex(
                """(?m)^﻿?[ \t]*(?:-[ \t]+)?(?:[\w.-]+[ \t]*:[ \t]*)?""" +
                    """&([\w-]+)[ \t]+"?([^\s"#]+)"?[ \t]*(?:#.*)?\r?$""",
            )

        fun prototypeIds(text: CharSequence): Map<String, String> {
            val entries = mutableMapOf<String, MutableList<String>>()

            // Robust resolves aliases against the whole document rather than against the text above
            // them — an unknown anchor becomes a placeholder that a second pass fills in
            // (`DataNodeParser.ParseAlias`) — so the anchors of the file are collected before any
            // alias is looked up. The scan is paid for only by files that declare an id this way.
            val anchors by lazy(LazyThreadSafetyMode.NONE) {
                ANCHOR.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            }

            var kind = ""
            for (match in DECLARATION.findAll(text)) {
                val declaredKind = match.groups[1]
                if (declaredKind != null) {
                    kind = declaredKind.value
                    continue
                }
                val declared = match.groups[2] ?: continue

                // `id: *BackgammonBoard` declares the id its anchor carries: by the time the
                // prototype is read the alias no longer exists. The offset stays on the alias, since
                // that is the line declaring the prototype and the one a jump should land on.
                val id =
                    if (declared.value.startsWith(ALIAS_PREFIX)) {
                        anchors[declared.value.substring(1)] ?: continue
                    } else {
                        declared.value
                    }
                entries.getOrPut(id) { mutableListOf() } += "$kind$KIND_SEPARATOR${declared.range.first}"
            }
            return entries.mapValues { (_, list) -> list.joinToString(ENTRY_SEPARATOR.toString()) }
        }

        fun parseEntries(value: String): List<Pair<String, Int>> =
            value.split(ENTRY_SEPARATOR)
                .mapNotNull { entry ->
                    val offset = entry.substringAfterLast(KIND_SEPARATOR).toIntOrNull() ?: return@mapNotNull null
                    entry.substringBeforeLast(KIND_SEPARATOR) to offset
                }
    }
}
