package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.ProjectScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * Files that mention a value shaped like a reference. Find Usages narrows the search to these and
 * then resolves every candidate, so a loose key set costs a wasted file, never a wrong result.
 *
 * The platform word index cannot do this job: its fallback scanner splits on non-word characters,
 * so a localization key lands in it as `comp`, `thief`, `target` and is never found whole.
 */
class RobustYamlValueIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension.equals("yml", ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, Void?, FileContent> =
        DataIndexer { content -> values(content.contentAsText) }

    companion object {
        val NAME: ID<String, Void> = ID.create("robustyaml.values")

        private val SCALAR =
            Regex("""(?m)^﻿?[ \t]*(?:-[ \t]+)?(?:[\w.]+[ \t]*:[ \t]*)?(\[[^\]\r\n]*\]|[^\s#\[\]]+)[ \t]*(?:#.*)?\r?$""")

        private val PROTOTYPE_ID = Regex("""[A-Z][A-Za-z0-9]*""")

        private val MESSAGE_ID = Regex("""[a-z][a-z0-9]*(?:-[a-z0-9]+)+""")

        fun values(text: CharSequence): Map<String, Void?> {
            val keys = mutableMapOf<String, Void?>()
            for (match in SCALAR.findAll(text)) {
                val value = match.groupValues[1]
                val items =
                    if (value.startsWith('[')) value.trim('[', ']').split(',')
                    else listOf(value)

                for (item in items) {
                    val trimmed = item.trim().trim('"')
                    if (PROTOTYPE_ID.matches(trimmed) || MESSAGE_ID.matches(trimmed)) keys[trimmed] = null
                }
            }
            return keys
        }

        fun files(project: Project, value: String): Collection<VirtualFile> =
            FileBasedIndex.getInstance()
                .getContainingFiles(NAME, value, ProjectScope.getAllScope(project))
    }
}
