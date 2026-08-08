package com.jetbrains.rider.plugins.robustyaml

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.jetbrains.rider.projectView.views.SolutionViewNode
import com.jetbrains.rider.projectView.views.solutionExplorer.SolutionExplorerRootProvider
import com.jetbrains.rider.projectView.views.solutionExplorer.SolutionExplorerViewSettings
import com.jetbrains.rider.projectView.views.solutionExplorer.nodes.SolutionExplorerFileNode

private const val NODE_NAME = "Robust Prototypes"

private val logger = logger<RobustPrototypeTreeRoot>()

class RobustPrototypeTreeRoot(private val project: Project) : SolutionExplorerRootProvider {
    override fun getRoots(settings: SolutionExplorerViewSettings): List<AbstractTreeNode<*>> {
        if (!RobustYamlSettings.getInstance(project).state.showPrototypeRoot) return emptyList()

        val roots = RobustPrototypeRootsProvider.findPrototypeRoots(project)
        if (roots.isEmpty()) return emptyList()

        logger.debug { "Solution view root with ${roots.size} directories" }
        return listOf(RobustPrototypeNode(project, roots, settings))
    }
}

class RobustPrototypeNode(
    project: Project,
    private val roots: List<VirtualFile>,
    private val settings: SolutionExplorerViewSettings,
) : SolutionViewNode<List<VirtualFile>>(project, roots) {
    override fun calculateChildren(): MutableList<AbstractTreeNode<*>> =
        roots.mapTo(mutableListOf()) {
            SolutionExplorerFileNode(myProject, it, emptyList(), settings, true, false)
        }

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.Nodes.PpLib)

        val problems = RobustProblemFiles.getInstance(myProject)
        problems.schedule()
        val severity =
            if (!RobustYamlSettings.getInstance(myProject).state.highlightProblemFiles) null
            else roots.mapNotNull { problems.severityOf(it) }.maxOrNull()

        val regular = SimpleTextAttributes.REGULAR_ATTRIBUTES
        presentation.addText(
            NODE_NAME,
            if (severity == null) regular else RobustProblemFiles.waved(regular, severity),
        )
    }

    override fun contains(file: VirtualFile): Boolean =
        roots.any { VfsUtilCore.isAncestor(it, file, false) }

    override fun getName(): String = NODE_NAME
}
