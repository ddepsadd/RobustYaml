package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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

    fun requiredForComponent(project: Project, component: String): List<String> =
        cache(project).computeIfAbsent(REQUIRED_PREFIX + RobustDataFieldIndex.COMPONENT_KEY + component) {
            requiredOf(project, it.removePrefix(REQUIRED_PREFIX))
        }

    private fun cached(project: Project, aliasKey: String): List<String> =
        cache(project).computeIfAbsent(aliasKey) { fieldsOf(project, it) }

    private fun requiredOf(project: Project, aliasKey: String): List<String> {
        val className = values(project, aliasKey).firstOrNull() ?: return emptyList()
        val required = sortedSetOf<String>()
        collectRequired(project, className, required, mutableSetOf())
        return required.toList()
    }

    private fun collectRequired(
        project: Project,
        className: String,
        into: MutableSet<String>,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(className) || visited.size > MAX_HIERARCHY) return

        for (value in values(project, RobustDataFieldIndex.CLASS_KEY + className)) {
            into += RobustDataFieldIndex.parseRequired(value)
            for (base in RobustDataFieldIndex.parseBases(value)) {
                collectRequired(project, base, into, visited)
            }
        }
    }

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

    fun rootClass(project: Project, declaration: RobustYamlContext.DeclarationContext): String? =
        if (declaration.isComponent) componentClass(project, declaration.name)
        else prototypeClass(project, declaration.name)

    fun componentClass(project: Project, component: String): String? =
        values(project, RobustDataFieldIndex.COMPONENT_KEY + component).firstOrNull()

    fun prototypeClass(project: Project, kind: String): String? =
        values(project, RobustDataFieldIndex.PROTOTYPE_KEY + kind).firstOrNull()

    fun ownerOfField(project: Project, className: String, field: String): String? {
        val queue = ArrayDeque(listOf(className))
        val visited = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current) || visited.size > MAX_HIERARCHY) continue
            for (value in values(project, RobustDataFieldIndex.CLASS_KEY + current)) {
                if (field in RobustDataFieldIndex.parseFields(value)) return current
                queue += RobustDataFieldIndex.parseBases(value)
            }
        }
        return null
    }

    fun basesOf(project: Project, className: String): List<String> =
        values(project, RobustDataFieldIndex.CLASS_KEY + className)
            .flatMap { RobustDataFieldIndex.parseBases(it) }
            .distinct()

    fun declaringFiles(project: Project, className: String): Collection<VirtualFile> =
        FileBasedIndex.getInstance().getContainingFiles(
            RobustDataFieldIndex.NAME,
            RobustDataFieldIndex.CLASS_KEY + className,
            ProjectScope.getContentScope(project),
        )

    private fun values(project: Project, key: String): List<String> =
        FileBasedIndex.getInstance()
            .getValues(RobustDataFieldIndex.NAME, key, ProjectScope.getContentScope(project))

    private const val MAX_HIERARCHY = 32
    private const val REQUIRED_PREFIX = "required@"
}
