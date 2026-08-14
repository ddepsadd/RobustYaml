package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.concurrent.ConcurrentHashMap

object RobustPrototypeIndex {
    fun kinds(project: Project): List<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val kinds = sortedSetOf<String>()
            FileBasedIndex.getInstance().processAllKeys(
                RobustPrototypeKindIndex.NAME,
                { kind ->
                    kinds += kind
                    true
                },
                ProjectScope.getContentScope(project),
                null,
            )
            CachedValueProvider.Result.create(
                kinds.toList(),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    fun isKnownKind(project: Project, kind: String): Boolean =
        kind.isNotEmpty() && findKindFile(project, kind) != null

    fun hasAnyKind(project: Project): Boolean = kinds(project).isNotEmpty()

    fun findKindFile(project: Project, kind: String): VirtualFile? =
        FileBasedIndex.getInstance()
            .getContainingFiles(
                RobustPrototypeKindIndex.NAME,
                kind,
                ProjectScope.getContentScope(project),
            )
            .firstOrNull()

    fun ids(project: Project): List<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val ids = sortedSetOf<String>()
            FileBasedIndex.getInstance().processAllKeys(
                RobustPrototypeIdIndex.NAME,
                { id ->
                    ids += id
                    true
                },
                prototypeScope(project),
                null,
            )
            CachedValueProvider.Result.create(
                ids.toList(),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    /** Whether any prototype is declared under this id — a binary search over the sorted [ids]. */
    fun declaresId(project: Project, id: String): Boolean =
        id.isNotEmpty() && ids(project).binarySearch(id) >= 0

    fun idsOfKind(project: Project, kind: String): List<String> {
        if (kind.isEmpty()) return emptyList()
        return idsByKind(project).getOrPut(kind) { collectIdsOfKind(project, kind) }
    }

    private fun idsByKind(project: Project): ConcurrentHashMap<String, List<String>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, List<String>>(),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    private fun collectIdsOfKind(project: Project, kind: String): List<String> {
        val ids = sortedSetOf<String>()
        FileBasedIndex.getInstance().processValues(
            RobustPrototypeIdsByKindIndex.NAME,
            kind,
            null,
            { _, value ->
                value.split(RobustPrototypeIdsByKindIndex.ID_SEPARATOR)
                    .filterTo(ids) { it.isNotEmpty() }
                true
            },
            prototypeScope(project),
        )
        return ids.toList()
    }

    fun findDeclarations(project: Project, id: String): List<YAMLKeyValue> {
        if (id.isEmpty()) return emptyList()

        val declarations = mutableListOf<YAMLKeyValue>()
        val psiManager = PsiManager.getInstance(project)
        for (site in sites(project, id)) {
            val element = psiManager.findFile(site.file)?.findElementAt(site.offset)
            PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
                ?.let { declarations += it }
        }
        return declarations
    }

    data class DeclarationSite(val kind: String, val file: VirtualFile, val offset: Int)

    fun sites(project: Project, id: String): List<DeclarationSite> {
        if (id.isEmpty()) return emptyList()

        val sites = mutableListOf<DeclarationSite>()
        FileBasedIndex.getInstance().processValues(
            RobustPrototypeIdIndex.NAME,
            id,
            null,
            { file, value ->
                for ((kind, offset) in RobustPrototypeIdIndex.parseEntries(value)) {
                    sites += DeclarationSite(kind, file, offset)
                }
                true
            },
            prototypeScope(project),
        )
        return sites
    }

    fun isKnownId(project: Project, id: String): Boolean =
        id.isNotEmpty() &&
            FileBasedIndex.getInstance()
                .getContainingFiles(RobustPrototypeIdIndex.NAME, id, prototypeScope(project))
                .isNotEmpty()

    fun hasAnyId(project: Project): Boolean = ids(project).isNotEmpty()

    fun prototypeScope(project: Project): GlobalSearchScope = ProjectScope.getAllScope(project)
}
