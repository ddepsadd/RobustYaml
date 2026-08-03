package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex

object RobustComponentIndex {
    fun componentNames(project: Project): List<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                collectNames(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    private fun collectNames(project: Project): List<String> {
        val names = sortedSetOf<String>()
        FileBasedIndex.getInstance().processAllKeys(
            RobustComponentNameIndex.NAME,
            { name ->
                names += name
                true
            },
            ProjectScope.getContentScope(project),
            null,
        )
        return names.toList()
    }

    fun isKnownComponent(project: Project, name: String): Boolean =
        name.isNotEmpty() && findComponentFile(project, name) != null

    fun hasAnyComponent(project: Project): Boolean =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val stoppedEarly = !FileBasedIndex.getInstance().processAllKeys(
                RobustComponentNameIndex.NAME,
                { false },
                ProjectScope.getContentScope(project),
                null,
            )
            CachedValueProvider.Result.create(
                stoppedEarly,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    fun findComponentFile(project: Project, name: String): VirtualFile? =
        FileBasedIndex.getInstance()
            .getContainingFiles(
                RobustComponentNameIndex.NAME,
                name,
                ProjectScope.getContentScope(project),
            )
            .firstOrNull()
}
