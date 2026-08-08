package com.jetbrains.rider.plugins.robustyaml

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class RobustYamlReferenceIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getVersion(): Int = 2

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension.equals("yml", ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, Void?, FileContent> =
        DataIndexer { content -> references(content.contentAsText) }

    companion object {
        val NAME: ID<String, Void> = ID.create("robustyaml.references")

        const val COMPONENT_PREFIX = "c:"
        const val PARENT_PREFIX = "p:"
        const val ID_PREFIX = "r:"

        private const val PARENT_KEY = "parent"

        private val COMPONENT =
            Regex("""(?m)^[ \t]+-[ \t]+type[ \t]*:[ \t]*"?(\w+)"?[ \t]*(?:#.*)?\r?$""")

        private val REFERENCE =
            Regex(
                """(?m)^﻿?[ \t]*(parent|proto|prototype|entity)[ \t]*:[ \t]*""" +
                    """(?:\[([^\]\r\n]*)\]|"?([^\s"#\[\]]+)"?)[ \t]*(?:#.*)?\r?$""",
            )

        private val NON_IDS = setOf("null", "true", "false")

        fun references(text: CharSequence): Map<String, Void?> {
            val keys = mutableMapOf<String, Void?>()
            for (match in COMPONENT.findAll(text)) {
                keys[COMPONENT_PREFIX + match.groupValues[1]] = null
            }
            for (match in REFERENCE.findAll(text)) {
                val prefix = if (match.groupValues[1] == PARENT_KEY) PARENT_PREFIX else ID_PREFIX
                val inline = match.groups[2]
                val ids = inline?.value?.split(',') ?: listOf(match.groupValues[3])
                for (id in ids) {
                    val trimmed = id.trim().trim('"')
                    if (looksLikeId(trimmed)) keys[prefix + trimmed] = null
                }
            }
            return keys
        }

        private fun looksLikeId(id: String): Boolean {
            if (id.isEmpty() || id in NON_IDS) return false
            if (id.first() == '!' || id.first() == '*' || id.first() == '&') return false
            if (id.first().isDigit() || id.toDoubleOrNull() != null) return false
            return id.all { it.isLetterOrDigit() || it == '_' || it == '.' }
        }
    }
}
