package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import java.util.concurrent.ConcurrentHashMap

object RobustDataFields {
    fun forComponent(project: Project, component: String): List<String> =
        cached(project, RobustDataFieldIndex.COMPONENT_KEY + component)

    fun forPrototype(project: Project, kind: String): List<String> =
        cached(project, RobustDataFieldIndex.PROTOTYPE_KEY + kind)

    private fun cached(project: Project, aliasKey: String): List<String> =
        cache(project).computeIfAbsent(aliasKey) { fieldsOf(project, it) }

    private fun cache(project: Project): ConcurrentHashMap<String, List<String>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, List<String>>(),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    private fun fieldsOf(project: Project, aliasKey: String): List<String> {
        val className = values(project, aliasKey).firstOrNull() ?: return emptyList()
        val fields = sortedSetOf<String>()
        collect(project, className, fields, mutableSetOf())
        return fields.toList()
    }

    private fun collect(
        project: Project,
        className: String,
        into: MutableSet<String>,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(className) || visited.size > MAX_HIERARCHY) return

        for (value in values(project, RobustDataFieldIndex.CLASS_KEY + className)) {
            into += RobustDataFieldIndex.parseFields(value)
            for (base in RobustDataFieldIndex.parseBases(value)) {
                collect(project, base, into, visited)
            }
        }
    }

    private fun values(project: Project, key: String): List<String> =
        FileBasedIndex.getInstance()
            .getValues(RobustDataFieldIndex.NAME, key, ProjectScope.getContentScope(project))

    private const val MAX_HIERARCHY = 32
}
