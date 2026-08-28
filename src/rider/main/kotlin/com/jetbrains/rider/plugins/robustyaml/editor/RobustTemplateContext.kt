package com.jetbrains.rider.plugins.robustyaml.editor

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.openapi.vfs.VfsUtilCore
import com.jetbrains.rider.plugins.robustyaml.project.RobustPrototypeRootsProvider

/**
 * Where the templates of this plugin may expand: a YAML file that lies in a directory of prototypes.
 * Not "inside a declaration", as the editor handlers ask — `ent` is typed on an empty line, where
 * there is no declaration around it yet — and not "any YAML", or the abbreviation would fire in a
 * `docker-compose.yml` of some other project.
 *
 * The question is asked of `originalFile`, and that is the whole difference between a
 * template that works and one that is never offered. `LiveTemplateCompletionContributor` puts
 * applicable templates into the lookup, and it builds the context from
 * `parameters.getPosition().getContainingFile()` — the copy completion makes with its dummy
 * identifier in it, whose virtual file is a light one that lies under no root at all. Answering
 * from the copy, the templates dropped out of the list, and typing `ent` brought up only the stock
 * YAML keys it is an infix of (`parent`, `placement`, `components`) — with the lookup open, Tab
 * belongs to the lookup, so the skeleton was never expanded. Expansion from a closed lookup went
 * on working the whole time: that path passes the real file.
 */
class RobustTemplateContext : TemplateContextType("Robust prototypes") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val psi = context.file.originalFile
        val file = psi.virtualFile ?: return false
        if (!file.name.endsWith(".yml", ignoreCase = true)) return false

        val roots = RobustPrototypeRootsProvider.findPrototypeRoots(psi.project)
        return roots.any { root -> VfsUtilCore.isAncestor(root, file, false) }
    }
}
