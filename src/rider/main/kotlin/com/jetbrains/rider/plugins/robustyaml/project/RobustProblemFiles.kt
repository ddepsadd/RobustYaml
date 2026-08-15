package com.jetbrains.rider.plugins.robustyaml.project

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.indexing.FileBasedIndex
import com.jetbrains.rider.plugins.robustyaml.highlighting.RobustYamlColors
import com.jetbrains.rider.plugins.robustyaml.index.RobustYamlReferenceIndex
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustComponentIndex
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class RobustProblemFiles(private val project: Project, private val scope: CoroutineScope) {
    enum class Severity { WARNING, ERROR }

    private data class Snapshot(val marks: Map<VirtualFile, Severity>)

    @Volatile
    private var snapshot = EMPTY

    private val pending = AtomicBoolean(false)

    init {
        project.messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith(YAML_SUFFIX) || it.path.endsWith(SOURCE_SUFFIX) }) schedule()
            }
        })
    }

    fun severityOf(file: VirtualFile): Severity? = snapshot.marks[file]

    fun schedule() {
        if (!pending.compareAndSet(false, true)) return
        scope.launch {
            delay(RECALCULATION_DELAY)
            pending.set(false)

            val computed = smartReadAction(project) { compute() }
            if (computed == snapshot) return@launch

            snapshot = computed
            logger.debug { "${computed.marks.size} prototype nodes with unknown references" }
            withContext(Dispatchers.EDT) { ProjectView.getInstance(project).refresh() }
        }
    }

    private fun compute(): Snapshot {
        val roots = RobustPrototypeRootsProvider.findPrototypeRoots(project)
        if (roots.isEmpty()) return EMPTY

        val components = RobustComponentIndex.componentNames(project).toSet()
        val ids = RobustPrototypeIndex.ids(project).toSet()
        if (components.isEmpty() || ids.isEmpty()) return EMPTY

        val index = FileBasedIndex.getInstance()
        val marks = mutableMapOf<VirtualFile, Severity>()
        for (root in roots) {
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                if (!file.isDirectory && file.extension.equals(YAML_EXTENSION, ignoreCase = true)) {
                    severityOf(index, file, components, ids)?.let { marks[file] = it }
                }
                true
            }
        }

        for ((file, severity) in marks.toList()) {
            var parent: VirtualFile? = file.parent
            while (parent != null) {
                val current = parent
                if (roots.none { VfsUtilCore.isAncestor(it, current, false) }) break
                val known = marks[current]
                if (known != null && known >= severity) break
                marks[current] = severity
                parent = current.parent
            }
        }
        return Snapshot(marks)
    }

    private fun severityOf(
        index: FileBasedIndex,
        file: VirtualFile,
        components: Set<String>,
        ids: Set<String>,
    ): Severity? {
        var severity: Severity? = null
        for (key in index.getFileData(RobustYamlReferenceIndex.NAME, file, project).keys) {
            val unknown = when {
                key.startsWith(RobustYamlReferenceIndex.COMPONENT_PREFIX) ->
                    if (key.removePrefix(RobustYamlReferenceIndex.COMPONENT_PREFIX) in components) null
                    else Severity.ERROR
                key.startsWith(RobustYamlReferenceIndex.PARENT_PREFIX) ->
                    if (key.removePrefix(RobustYamlReferenceIndex.PARENT_PREFIX) in ids) null
                    else Severity.ERROR
                key.startsWith(RobustYamlReferenceIndex.ID_PREFIX) ->
                    if (key.removePrefix(RobustYamlReferenceIndex.ID_PREFIX) in ids) null
                    else Severity.WARNING
                else -> null
            }
            if (unknown == Severity.ERROR) return Severity.ERROR
            if (unknown != null) severity = unknown
        }
        return severity
    }

    companion object {
        fun getInstance(project: Project): RobustProblemFiles = project.service()

        fun waved(source: SimpleTextAttributes, severity: Severity): SimpleTextAttributes =
            SimpleTextAttributes(
                source.style or SimpleTextAttributes.STYLE_WAVED,
                source.fgColor,
                RobustYamlColors.waveColor(severity == Severity.ERROR),
            )

        private const val RECALCULATION_DELAY = 1500L
        private const val YAML_EXTENSION = "yml"
        private const val YAML_SUFFIX = ".yml"
        private const val SOURCE_SUFFIX = ".cs"

        private val EMPTY = Snapshot(emptyMap())

        private val logger = logger<RobustProblemFiles>()
    }
}
