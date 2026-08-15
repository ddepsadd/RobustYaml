package com.jetbrains.rider.plugins.robustyaml.project

import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Prototypes and locale files are attached as a synthetic library, so the platform counts them as
 * "non-project" and guards every write to them — a rename of an id stops before the input dialog
 * even opens. They are ordinary files of the checkout the user is editing, so writing is allowed.
 */
class RobustFileWritingAccess(private val project: Project) : NonProjectFileWritingAccessExtension {
    override fun isWritable(file: VirtualFile): Boolean =
        RobustPrototypeRootsProvider.indexedRoots(project).any { root ->
            VfsUtilCore.isAncestor(root, file, false)
        }
}
