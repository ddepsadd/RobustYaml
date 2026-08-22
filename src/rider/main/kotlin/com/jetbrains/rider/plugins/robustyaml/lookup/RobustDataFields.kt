package com.jetbrains.rider.plugins.robustyaml.lookup

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.index.RobustDataFieldIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
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

    /**
     * Whether a YAML key of this name names a prototype by id. The kind is not answered here and
     * cannot be: it belongs to the owning type, and the owner is what the backend knows. This is the
     * weaker question, and the only one that can be answered about a file nobody has opened — which
     * is exactly the situation Find Usages and rename work in, walking hundreds of files for which
     * no type was ever requested.
     *
     * A name counts only when nothing in the checkout declares it as anything else. Measured on
     * ss14-wega: 836 names are a prototype id somewhere, 133 of them are something else elsewhere
     * (`name`, `values`, `tags`, `key`, `layer`), and dropping those turns 85244 values that are no
     * ids at all into 11. The remaining 703 names carry 17143 values, of which 17132 are ids
     * declared in the content — references the plugin could not see before.
     */
    fun namesPrototype(project: Project, key: String): Boolean = key in prototypeKeys(project)

    /**
     * The same question asked of a value rather than of a name, because the name over a value is not
     * always the one that knows. Under a datafield declared `Dictionary<string, EntProtoId>` the
     * keys are the author's own — slot names in `equipment:`, one per line — and the id stands to
     * the right of a key that is a datafield of nothing. So the value is a reference when its own
     * key says so, or when the mapping it lies in belongs to a key that does.
     *
     * One level up, not any: `mask:` under `equipment:` is the shape the content writes, and looking
     * further would let a name several nestings away claim values it knows nothing about.
     */
    fun namesPrototype(keyValue: YAMLKeyValue): Boolean {
        val project = keyValue.project
        if (namesPrototype(project, keyValue.keyText)) return true
        val owner = RobustYamlContext.mappingOwner(keyValue) ?: return false
        return owner.keyText in prototypeValueKeys(project)
    }

    private fun prototypeKeys(project: Project): Set<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val named = keysWithPrefix(project, RobustDataFieldIndex.PROTOTYPE_FIELD_KEY)
            named -= keysWithPrefix(project, RobustDataFieldIndex.PLAIN_FIELD_KEY)
            CachedValueProvider.Result.create(named, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }

    /** Names whose mapping holds ids in its values, filtered by the same ambiguity test. */
    private fun prototypeValueKeys(project: Project): Set<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val named = keysWithPrefix(project, RobustDataFieldIndex.PROTOTYPE_VALUE_FIELD_KEY)
            named -= keysWithPrefix(project, RobustDataFieldIndex.PLAIN_FIELD_KEY)
            CachedValueProvider.Result.create(named, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }

    private fun keysWithPrefix(project: Project, prefix: String): MutableSet<String> {
        val names = mutableSetOf<String>()
        FileBasedIndex.getInstance().processAllKeys(
            RobustDataFieldIndex.NAME,
            { key ->
                if (key.startsWith(prefix)) names += key.substring(prefix.length)
                true
            },
            project,
        )
        return names
    }

    private fun values(project: Project, key: String): List<String> =
        FileBasedIndex.getInstance()
            .getValues(RobustDataFieldIndex.NAME, key, ProjectScope.getContentScope(project))

    private const val MAX_HIERARCHY = 32
    private const val REQUIRED_PREFIX = "required@"
}
