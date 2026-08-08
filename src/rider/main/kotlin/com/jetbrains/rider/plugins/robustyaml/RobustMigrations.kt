package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

object RobustMigrations {
    fun renamedTo(project: Project, id: String): String? =
        entries(project)[id]?.takeIf { it.isNotEmpty() }

    fun isRemoved(project: Project, id: String): Boolean = entries(project)[id] == ""

    private fun entries(project: Project): Map<String, String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                load(project),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    private fun load(project: Project): Map<String, String> {
        val file = RobustResources.roots(project).firstNotNullOfOrNull { it.findChild(FILE_NAME) }
            ?: return emptyMap()
        val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return emptyMap()
        return parse(text)
    }

    fun parse(text: CharSequence): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        for (match in ENTRY.findAll(text)) {
            val target = match.groupValues[2]
            entries[match.groupValues[1]] = if (target == NULL_VALUE) "" else target
        }
        return entries
    }

    private const val FILE_NAME = "migration.yml"
    private const val NULL_VALUE = "null"

    private val ENTRY =
        Regex("""(?m)^﻿?([A-Za-z_][\w.]*)[ \t]*:[ \t]*"?([\w.]*)"?[ \t]*(?:#.*)?\r?$""")
}
