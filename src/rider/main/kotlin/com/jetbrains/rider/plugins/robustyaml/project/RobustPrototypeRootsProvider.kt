package com.jetbrains.rider.plugins.robustyaml.project

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustResources
import java.nio.file.Path
import javax.swing.Icon

class RobustPrototypeLibrary(private val roots: List<VirtualFile>) : SyntheticLibrary(), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = roots

    override fun getPresentableText(): String = "Robust Prototypes"

    override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.PpLib

    override fun equals(other: Any?): Boolean = other is RobustPrototypeLibrary && other.roots == roots

    override fun hashCode(): Int = roots.hashCode()
}

class RobustPrototypeRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val roots = indexedRoots(project)
        if (roots.isEmpty()) return emptyList()
        return listOf(RobustPrototypeLibrary(roots))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> = indexedRoots(project)

    companion object {
        private const val LIBRARY_NAME = "Robust Prototypes"

        private val logger = logger<RobustPrototypeRootsProvider>()

        fun indexedRoots(project: Project): List<VirtualFile> =
            findPrototypeRoots(project) + findLocaleRoots(project) + findGuidebookRoots(project)

        fun findLocaleRoots(project: Project): List<VirtualFile> =
            CachedValuesManager.getManager(project).getCachedValue(project) {
                CachedValueProvider.Result.create(
                    computeLocaleRoots(project),
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    ProjectRootManager.getInstance(project),
                )
            }

        /**
         * Guidebook documents sit beside the prototypes and name them — 1240 embeds in ss14-wega —
         * and they hold localization keys as well. Written out rather than shared with
         * [findLocaleRoots]: the cache is keyed by the class of the provider lambda, so one function
         * taking the directory as an argument would give both roots the same key and let them
         * overwrite each other.
         */
        fun findGuidebookRoots(project: Project): List<VirtualFile> =
            CachedValuesManager.getManager(project).getCachedValue(project) {
                CachedValueProvider.Result.create(
                    computeGuidebookRoots(project),
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    ProjectRootManager.getInstance(project),
                )
            }

        private fun computeLocaleRoots(project: Project): List<VirtualFile> {
            val base = project.guessProjectDir() ?: return emptyList()
            val roots = RobustResources.resourceRoots(base)
                .mapNotNull { it.findChild(LOCALE_DIR) }
                .filter { it.isValid && it.isDirectory }
                .distinct()
            logger.debug { "Locale roots under ${base.path}: ${roots.joinToString { it.path }}" }
            return roots
        }

        private fun computeGuidebookRoots(project: Project): List<VirtualFile> {
            val base = project.guessProjectDir() ?: return emptyList()
            val roots = RobustResources.resourceRoots(base)
                .mapNotNull { it.findChild(GUIDEBOOK_DIR) }
                .filter { it.isValid && it.isDirectory }
                .distinct()
            logger.debug { "Guidebook roots under ${base.path}: ${roots.joinToString { it.path }}" }
            return roots
        }

        fun findPrototypeRoots(project: Project): List<VirtualFile> =
            CachedValuesManager.getManager(project).getCachedValue(project) {
                CachedValueProvider.Result.create(
                    computeRoots(project),
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    ProjectRootManager.getInstance(project),
                    RobustYamlSettings.getInstance(project).state,
                )
            }

        private fun computeRoots(project: Project): List<VirtualFile> {
            val base = project.guessProjectDir()
            if (base == null) {
                logger.debug("No project dir, no prototype roots")
                return emptyList()
            }
            val settings = RobustYamlSettings.getInstance(project).state
            val detected = if (settings.autoDetect) autoDetect(base) else emptyList()
            val custom = settings.customRoots.mapNotNull { resolve(base, it) }
            val roots = (detected + custom).filter { it.isValid && it.isDirectory }.distinct()
            logger.debug { "Prototype roots under ${base.path}: ${roots.joinToString { it.path }}" }
            return roots
        }

        fun rootsChanged(project: Project, oldRoots: Collection<VirtualFile>) {
            WriteAction.run<RuntimeException> {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project,
                    LIBRARY_NAME,
                    oldRoots,
                    indexedRoots(project),
                    LIBRARY_NAME,
                )
            }
        }

        private const val MAX_SCANNED_ENTRIES = 5000
        private const val PROTOTYPES_SUFFIX = "Prototypes"
        private const val LOCALE_DIR = "Locale"
        private const val GUIDEBOOK_DIR = "ServerInfo"

        private fun autoDetect(base: VirtualFile): List<VirtualFile> =
            (listOf(base) + base.children.filter { it.isDirectory } + RobustResources.resourceRoots(base))
                .distinct()
                .flatMap { it.children.asSequence().filter { child -> child.isDirectory } }
                .filter { it.name.endsWith(PROTOTYPES_SUFFIX, ignoreCase = true) && containsYaml(it) }
                .distinct()

        private fun containsYaml(dir: VirtualFile): Boolean {
            val queue = ArrayDeque(listOf(dir))
            var scanned = 0
            while (queue.isNotEmpty() && scanned < MAX_SCANNED_ENTRIES) {
                for (child in queue.removeFirst().children) {
                    scanned++
                    if (child.isDirectory) queue += child
                    else if (child.extension.equals("yml", ignoreCase = true)) return true
                }
            }
            return false
        }

        private fun resolve(base: VirtualFile, path: String): VirtualFile? {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) return null
            val nio = runCatching { Path.of(trimmed) }.getOrNull() ?: return null
            return if (nio.isAbsolute) VirtualFileManager.getInstance().findFileByNioPath(nio)
            else base.findFileByRelativePath(trimmed)
        }
    }
}
