package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

object RobustResources {
    fun roots(project: Project): List<VirtualFile> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                collectRoots(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    fun resolve(project: Project, path: String): VirtualFile? {
        if (!looksLikePath(path)) return null
        val relative = if (path.startsWith("/")) path.substring(1) else "$TEXTURES_DIR/$path"
        return roots(project).firstNotNullOfOrNull { it.findFileByRelativePath(relative) }
    }

    fun looksLikePath(value: String): Boolean {
        if (value.isEmpty() || value == "null") return false
        if (value.contains('\\') || value.any { it.isWhitespace() }) return false
        return value.contains('/') ||
            value.substringAfterLast('.', "").lowercase() in KNOWN_EXTENSIONS
    }

    private fun collectRoots(project: Project): List<VirtualFile> {
        val base = project.guessProjectDir() ?: return emptyList()
        val roots = mutableListOf<VirtualFile>()
        RobustPrototypeRootsProvider.findPrototypeRoots(project).mapNotNullTo(roots) { it.parent }
        base.findChild(RESOURCES_DIR)?.let { roots += it }
        base.children.filter { it.isDirectory }
            .mapNotNullTo(roots) { it.findChild(RESOURCES_DIR) }
        return roots.filter { it.isValid && it.isDirectory }.distinct()
    }

    private const val RESOURCES_DIR = "Resources"
    private const val TEXTURES_DIR = "Textures"

    private val KNOWN_EXTENSIONS =
        setOf("rsi", "png", "ogg", "wav", "ttf", "otf", "swsl", "json", "yml", "toml")
}
