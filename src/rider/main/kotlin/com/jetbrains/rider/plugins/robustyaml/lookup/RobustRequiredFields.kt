package com.jetbrains.rider.plugins.robustyaml.lookup

import com.intellij.openapi.project.Project
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import java.util.concurrent.ConcurrentHashMap

object RobustRequiredFields {
    fun missing(typeKey: YAMLKeyValue): List<String> {
        if (!RobustYamlContext.isComponentTypeKey(typeKey)) return emptyList()

        val component = (typeKey.value as? YAMLScalar)?.textValue ?: return emptyList()
        if (component.isEmpty()) return emptyList()

        val componentMapping = typeKey.parentMapping ?: return emptyList()
        val prototype = prototypeOf(componentMapping) ?: return emptyList()
        if (kindOf(prototype) != ENTITY_KIND || isAbstract(prototype)) return emptyList()

        val project = typeKey.project
        val required = RobustDataFields.requiredForComponent(project, component)
        if (required.isEmpty()) return emptyList()

        val declared = keysOf(componentMapping)
        val pending = required.filter { it !in declared }
        if (pending.isEmpty()) return emptyList()

        val inherited = mutableSetOf<String>()
        for (parent in parentsOf(prototype)) {
            inherited += inheritedKeys(project, parent, component)
        }
        return pending.filter { it !in inherited }
    }

    fun message(typeKey: YAMLKeyValue, missing: List<String>): String {
        val component = (typeKey.value as? YAMLScalar)?.textValue.orEmpty()
        val fields = missing.joinToString("', '", "'", "'")
        val subject = if (missing.size == 1) "Required field" else "Required fields"
        return "$subject $fields of component '$component' are not set"
    }

    private fun inheritedKeys(project: Project, id: String, component: String): Set<String> =
        cache(project).computeIfAbsent("$id@$component") {
            collect(project, id, component, mutableSetOf())
        }

    private fun collect(
        project: Project,
        id: String,
        component: String,
        visited: MutableSet<String>,
    ): Set<String> {
        if (!visited.add(id) || visited.size > MAX_CHAIN) return emptySet()

        val keys = mutableSetOf<String>()
        for (declaration in RobustPrototypeIndex.findDeclarations(project, id)) {
            val prototype = PsiTreeUtil.getParentOfType(declaration, YAMLMapping::class.java, true)
                ?: continue

            componentMapping(prototype, component)?.let { keys += keysOf(it) }
            for (parent in parentsOf(prototype)) {
                keys += collect(project, parent, component, visited)
            }
        }
        return keys
    }

    private fun cache(project: Project): ConcurrentHashMap<String, Set<String>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, Set<String>>(),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    private fun componentMapping(prototype: YAMLMapping, component: String): YAMLMapping? {
        val components = prototype.getKeyValueByKey(COMPONENTS_KEY)?.value as? YAMLSequence ?: return null
        for (item in components.items) {
            val mapping = item.value as? YAMLMapping ?: continue
            val name = (mapping.getKeyValueByKey(TYPE_KEY)?.value as? YAMLScalar)?.textValue
            if (name == component) return mapping
        }
        return null
    }

    private fun keysOf(mapping: YAMLMapping): Set<String> =
        mapping.keyValues.mapNotNullTo(mutableSetOf()) { it.keyText.takeIf { key -> key != TYPE_KEY } }

    private fun prototypeOf(componentMapping: YAMLMapping): YAMLMapping? =
        PsiTreeUtil.getParentOfType(componentMapping, YAMLMapping::class.java, true)

    private fun kindOf(prototype: YAMLMapping): String? =
        (prototype.getKeyValueByKey(TYPE_KEY)?.value as? YAMLScalar)?.textValue

    private fun isAbstract(prototype: YAMLMapping): Boolean =
        (prototype.getKeyValueByKey(ABSTRACT_KEY)?.value as? YAMLScalar)?.textValue
            .equals("true", ignoreCase = true)

    private fun parentsOf(prototype: YAMLMapping): List<String> = RobustYamlContext.parentIds(prototype)

    private const val ENTITY_KIND = "entity"
    private const val COMPONENTS_KEY = "components"
    private const val TYPE_KEY = "type"
    private const val ABSTRACT_KEY = "abstract"
    private const val MAX_CHAIN = 64
}
