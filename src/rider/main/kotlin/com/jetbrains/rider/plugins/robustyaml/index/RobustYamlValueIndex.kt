package com.jetbrains.rider.plugins.robustyaml.index

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
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustGuidebook

/**
 * Files that mention a value shaped like a reference. Find Usages narrows the search to these and
 * then resolves every candidate, so a loose key set costs a wasted file, never a wrong result.
 *
 * The platform word index cannot do this job: its fallback scanner splits on non-word characters,
 * so a localization key lands in it as `comp`, `thief`, `target` and is never found whole.
 */
class RobustYamlValueIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getVersion(): Int = 5

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file ->
            file.extension.equals(YAML_EXTENSION, ignoreCase = true) ||
                file.extension.equals(RobustGuidebook.EXTENSION, ignoreCase = true)
        }

    override fun getIndexer(): DataIndexer<String, Void?, FileContent> =
        DataIndexer { content -> values(content.contentAsText, content.file.extension.orEmpty()) }

    companion object {
        val NAME: ID<String, Void> = ID.create("robustyaml.values")

        // The name of a key may hold dashes — `accent-cowboy-words-1: accent-cowboy-replacement-1`
        // is a mapping of one message onto another — and a key that fails to parse takes the value
        // with it, hiding every usage written that way. An anchor is allowed for the same reason:
        // `tooltip: &TextOpenClose door-remote-open-close-text` otherwise matched nothing at all,
        // because `&TextOpenClose` was read as the value and the text after it broke the line.
        private val SCALAR =
            Regex(
                """(?m)^﻿?[ \t]*(?:-[ \t]+)?(?:([\w.-]+)[ \t]*:[ \t]*)?(?:&[\w-]+[ \t]+)?""" +
                    """(\[[^\]\r\n]*\]|[^\s#\[\]]+)[ \t]*(?:#.*)?\r?$""",
            )

        private val PROTOTYPE_ID = Regex("""[A-Z][A-Za-z0-9]*""")

        /**
         * Upper case and underscores are allowed because keys carry names: `job-description-wardenHelper`,
         * `contraband-examine-text-GrandTheft`, `tts-voice-name-Cyberpunk_dexter`. A lower-case-only
         * form left 330 such usages out of the index, and a key nobody can find looks unused.
         */
        private val MESSAGE_ID = Regex("""[A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+""")

        const val YAML_EXTENSION = "yml"

        /**
         * A guidebook document names prototypes too — `<GuideEntityEmbed Entity="Crowbar"/>` — and
         * without them the whole guide is invisible to Find Usages and to a rename.
         */
        fun values(text: CharSequence, extension: String): Map<String, Void?> =
            if (extension.equals(RobustGuidebook.EXTENSION, ignoreCase = true)) {
                RobustGuidebook.ids(text).associateWith { null }
            } else {
                values(text)
            }

        fun values(text: CharSequence): Map<String, Void?> {
            val keys = mutableMapOf<String, Void?>()
            for (match in SCALAR.findAll(text)) {
                // The name of a key is a reference too when a mapping is keyed by messages:
                // `accent-cowboy-words-1: accent-cowboy-replacement-1` mentions two of them.
                val name = match.groupValues[1]
                if (MESSAGE_ID.matches(name)) keys[name] = null

                val value = match.groupValues[2]
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
