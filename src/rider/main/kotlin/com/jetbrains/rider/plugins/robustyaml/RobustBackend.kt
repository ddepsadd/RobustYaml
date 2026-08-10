package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.jetbrains.rd.framework.util.asCoroutineDispatcher
import com.jetbrains.rd.ide.model.RobustDataField
import com.jetbrains.rd.ide.model.RobustFieldQuery
import com.jetbrains.rd.ide.model.RobustFieldsReply
import com.jetbrains.rd.ide.model.RobustImplementationsReply
import com.jetbrains.rd.ide.model.robustYamlModel
import com.jetbrains.rider.projectView.hasSolution
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class RobustBackend(private val project: Project, private val scope: CoroutineScope) {
    private val cache = ConcurrentHashMap<String, Deferred<List<RobustDataField>>>()
    private val ready = ConcurrentHashMap<String, List<RobustDataField>>()
    private val implementationCache = ConcurrentHashMap<String, Deferred<List<String>>>()
    private val implementationsReady = ConcurrentHashMap<String, List<String>>()
    private val restartPending = AtomicBoolean(false)

    init {
        project.messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.none { it.path.endsWith(".cs") }) return
                cache.clear()
                ready.clear()
                implementationCache.clear()
                implementationsReady.clear()
            }
        })
    }

    /**
     * Classes a `!type:` tag may name for the field at the end of [path]. Same contract as
     * [cachedFields]: a miss starts the call and answers null, because the annotator and the
     * completion both run under a read action and must not wait for the protocol scheduler.
     */
    fun cachedImplementations(className: String, path: List<String>): List<String>? {
        if (!project.hasSolution) return null
        val key = keyOf(className, path)
        implementationsReady[key]?.let { return it }
        implementationCache.computeIfAbsent(key) { loadImplementations(it, className, path) }
        return null
    }

    suspend fun typeFields(className: String, path: List<String> = emptyList()): List<RobustDataField> {
        if (!project.hasSolution) return emptyList()
        val key = keyOf(className, path)
        return cache.computeIfAbsent(key) { load(it, className, path) }.await()
    }

    suspend fun field(className: String, path: List<String>, field: String): RobustDataField? =
        typeFields(className, path).firstOrNull { it.name == field }

    fun cachedFields(className: String, path: List<String> = emptyList()): List<RobustDataField>? {
        if (!project.hasSolution) return null
        val key = keyOf(className, path)
        ready[key]?.let { return it }
        cache.computeIfAbsent(key) { load(it, className, path) }
        return null
    }

    fun cachedField(className: String, path: List<String>, field: String): RobustDataField? =
        cachedFields(className, path)?.firstOrNull { it.name == field }

    private fun keyOf(className: String, path: List<String>): String =
        (listOf(className) + path).joinToString("/")

    private fun scheduleRestart() {
        if (!restartPending.compareAndSet(false, true)) return
        scope.launch {
            delay(RESTART_DELAY)
            restartPending.set(false)
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    private fun load(
        key: String,
        className: String,
        path: List<String>,
    ): Deferred<List<RobustDataField>> = scope.async {
        runCatching {
            val model = project.solution.robustYamlModel
            val scheduler = model.protocol?.scheduler
                ?: return@runCatching RobustFieldsReply(false, emptyList())
            withContext(scheduler.asCoroutineDispatcher) {
                model.typeFields.startSuspending(RobustFieldQuery(className, path))
            }
        }.onSuccess { reply ->
            if (logger.isDebugEnabled) {
                val kinds = reply.fields.mapNotNull { field ->
                    val kind = field.prototypeKind ?: field.keyPrototypeKind ?: return@mapNotNull null
                    "${field.name}=$kind"
                }.joinToString()
                val docs = reply.fields.count { it.summary != null }
                val state = if (reply.resolved) "resolved" else "unbuilt"
                logger.debug(
                    "Backend returned ${reply.fields.size} fields ($docs documented, $state) for '$key' [$kinds]",
                )
            }
            if (!reply.resolved) {
                cache.remove(key)
            } else {
                ready[key] = reply.fields
                if (reply.fields.isNotEmpty()) scheduleRestart()
            }
        }.onFailure {
            logger.warn("Backend call failed for '$key'", it)
            cache.remove(key)
        }.map { it.fields }.getOrDefault(emptyList())
    }

    private fun loadImplementations(
        key: String,
        className: String,
        path: List<String>,
    ): Deferred<List<String>> = scope.async {
        runCatching {
            val model = project.solution.robustYamlModel
            val scheduler = model.protocol?.scheduler
                ?: return@runCatching RobustImplementationsReply(false, emptyList())
            withContext(scheduler.asCoroutineDispatcher) {
                model.typeImplementations.startSuspending(RobustFieldQuery(className, path))
            }
        }.onSuccess { reply ->
            logger.debug { "Backend returned ${reply.names.size} implementations for '$key'" }
            if (!reply.resolved) {
                implementationCache.remove(key)
            } else {
                implementationsReady[key] = reply.names
                if (reply.names.isNotEmpty()) scheduleRestart()
            }
        }.onFailure {
            logger.warn("Backend call failed for implementations of '$key'", it)
            implementationCache.remove(key)
        }.map { it.names }.getOrDefault(emptyList())
    }

    companion object {
        private const val RESTART_DELAY = 300L

        private val logger = logger<RobustBackend>()

        fun getInstance(project: Project): RobustBackend = project.service()
    }
}
