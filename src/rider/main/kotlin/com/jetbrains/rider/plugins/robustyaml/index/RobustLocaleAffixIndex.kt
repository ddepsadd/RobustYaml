package com.jetbrains.rider.plugins.robustyaml.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * Ends of keys that no file spells in full. A key is assembled in three shapes, and each of them was
 * found by being wrong about it:
 *
 * - a head, `Loc.GetString($"accent-{name}-replacement")` and `"cmd-" + command`;
 * - a run named by a `localizedDataset` prototype, which gives a prefix and a count;
 * - a tail, `node.Description.Replace("-desc", "-tooltip")` — the key is derived from another key,
 *   and nothing anywhere holds it whole.
 *
 * A head ends with a dash and a tail begins with one, so the two live in the same index and are told
 * apart by their own shape. Tails are only taken from a file that calls `GetString`: without that
 * `"-c"`, `"-o"` and `"-unshaded"` come along, and each of them would silence a whole family of keys.
 *
 * The match is by the ends alone, deliberately: `accent-{name}-replacement` says nothing about what
 * stands between. That is the loose direction on purpose — a dead key hidden behind a live affix
 * stays quiet, whereas the strict reading would accuse working code, which is what happened to
 * `heretic-know-ashgrasp-tooltip`.
 */
class RobustLocaleAffixIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file ->
            val extension = file.extension ?: return@InputFilter false
            extension.equals(RobustLocaleUsageIndex.CODE_EXTENSION, ignoreCase = true) ||
                extension.equals(PROTOTYPE_EXTENSION, ignoreCase = true)
        }

    override fun getIndexer(): DataIndexer<String, Void?, FileContent> =
        DataIndexer { content ->
            affixes(content.contentAsText, content.file.extension.orEmpty()).associateWith { null }
        }

    companion object {
        val NAME: ID<String, Void> = ID.create("robustyaml.locale.affixes")

        const val PROTOTYPE_EXTENSION = "yml"

        /** `$"accent-{name}-replacement"` and `"cmd-" + command`, the two ways a head is written. */
        private val HEAD = Regex(""""([A-Za-z][\w-]*-)(?:\{|"[ \t]*\+)""")

        /** `"-tooltip"` handed to `Replace` or `EndsWith`: the key is one derived from another. */
        private val TAIL = Regex(""""(-[A-Za-z][\w-]*)"""")

        /** `localizedDataset` names its messages by a prefix and a count, never one by one. */
        private val DATASET = Regex("""(?m)^[ \t]*prefix:[ \t]*"?([A-Za-z][\w-]*-)"?[ \t]*\r?$""")

        private const val LOCALIZATION_CALL = "GetString"

        fun affixes(text: CharSequence, extension: String): Set<String> = when {
            extension.equals(RobustLocaleUsageIndex.CODE_EXTENSION, ignoreCase = true) -> {
                val found = HEAD.findAll(text).mapTo(mutableSetOf()) { it.groupValues[1] }
                if (text.contains(LOCALIZATION_CALL)) {
                    TAIL.findAll(text).mapTo(found) { it.groupValues[1] }
                }
                found
            }
            extension.equals(PROTOTYPE_EXTENSION, ignoreCase = true) ->
                DATASET.findAll(text).mapTo(mutableSetOf()) { it.groupValues[1] }
            else -> emptySet()
        }

        /** Whether an affix of the checkout covers this key, by either end. */
        fun covers(project: Project, id: String): Boolean =
            all(project).any { if (it.startsWith('-')) id.endsWith(it) else id.startsWith(it) }

        /**
         * Every affix of the checkout, held onto until the files change: the list is 364 long on
         * ss14-wega and every message of an opened `.ftl` is checked against all of it.
         */
        fun all(project: Project): List<String> =
            CachedValuesManager.getManager(project).getCachedValue(project) {
                val affixes = sortedSetOf<String>()
                FileBasedIndex.getInstance().processAllKeys(
                    NAME,
                    { affix ->
                        affixes += affix
                        true
                    },
                    ProjectScope.getAllScope(project),
                    null,
                )
                CachedValueProvider.Result.create(
                    affixes.toList(),
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                )
            }
    }
}
