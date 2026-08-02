package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

object RobustComponentIndex {
    private val COMPONENT_FILE = Regex("""^(\w+)Component\.cs$""")

    fun componentNames(project: Project): List<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                computeNames(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    private fun computeNames(project: Project): List<String> {
        val names = sortedSetOf<String>()
        FilenameIndex.processAllFileNames(
            { name ->
                COMPONENT_FILE.find(name)?.let { names += it.groupValues[1] }
                true
            },
            ProjectScope.getContentScope(project),
            null,
        )
        return names.toList()
    }
}
