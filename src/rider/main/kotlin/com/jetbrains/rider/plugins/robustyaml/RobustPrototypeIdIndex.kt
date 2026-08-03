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

    override fun getVersion(): Int = 3

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

        private val DECLARATION =
            Regex(
                """(?m)^﻿?(?:-[ \t]+type[ \t]*:[ \t]*(\w+)""" +
                    """|[ ]{0,2}id[ \t]*:[ \t]*"?([^\s"#]+)"?)[ \t]*(?:#.*)?\r?$""",
            )

        fun prototypeIds(text: CharSequence): Map<String, String> {
            val entries = mutableMapOf<String, MutableList<String>>()
            var kind = ""
            for (match in DECLARATION.findAll(text)) {
                val declaredKind = match.groups[1]
                if (declaredKind != null) {
                    kind = declaredKind.value
                    continue
                }
                val id = match.groups[2] ?: continue
                entries.getOrPut(id.value) { mutableListOf() } += "$kind$KIND_SEPARATOR${id.range.first}"
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
