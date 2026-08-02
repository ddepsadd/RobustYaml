package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile

class RobustPrototypeRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val roots = findPrototypeRoots(project)
        if (roots.isEmpty()) return emptyList()
        return listOf(SyntheticLibrary.newImmutableLibrary(roots))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> = findPrototypeRoots(project)

    private fun findPrototypeRoots(project: Project): List<VirtualFile> {
        val base = project.guessProjectDir() ?: return emptyList()
        return base.children
            .filter { it.isDirectory }
            .mapNotNull { it.findChild("Prototypes") }
            .filter { it.isDirectory }
    }
}
