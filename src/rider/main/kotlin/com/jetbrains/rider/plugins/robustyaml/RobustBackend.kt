package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.jetbrains.rd.framework.util.asCoroutineDispatcher
import com.jetbrains.rd.ide.model.RobustDataField
import com.jetbrains.rd.ide.model.RobustFieldQuery
import com.jetbrains.rd.ide.model.robustYamlModel
import com.jetbrains.rider.projectView.hasSolution
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class RobustBackend(private val project: Project, private val scope: CoroutineScope) {
    private val cache = ConcurrentHashMap<String, Deferred<List<RobustDataField>>>()

    init {
        project.messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.none { it.path.endsWith(".cs") }) return
                cache.clear()
            }
        })
    }

    suspend fun typeFields(className: String, path: List<String> = emptyList()): List<RobustDataField> {
        if (!project.hasSolution) return emptyList()
        val key = (listOf(className) + path).joinToString("/")
        return cache.computeIfAbsent(key) { load(it, className, path) }.await()
    }

    suspend fun field(className: String, path: List<String>, field: String): RobustDataField? =
        typeFields(className, path).firstOrNull { it.name == field }

    private fun load(
        key: String,
        className: String,
        path: List<String>,
    ): Deferred<List<RobustDataField>> = scope.async {
        runCatching {
            val model = project.solution.robustYamlModel
            val scheduler = model.protocol?.scheduler
                ?: return@runCatching emptyList<RobustDataField>()
            withContext(scheduler.asCoroutineDispatcher) {
                model.typeFields.startSuspending(RobustFieldQuery(className, path))
            }
        }.onSuccess {
            logger.info("Backend returned ${it.size} fields for '$key'")
        }.onFailure {
            logger.info("Backend call failed for '$key'", it)
            cache.remove(key)
        }.getOrDefault(emptyList())
    }

    companion object {
        private val logger = logger<RobustBackend>()

        fun getInstance(project: Project): RobustBackend = project.service()
    }
}
