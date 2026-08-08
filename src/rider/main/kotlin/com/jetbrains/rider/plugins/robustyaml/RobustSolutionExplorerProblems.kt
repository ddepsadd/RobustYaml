package com.jetbrains.rider.plugins.robustyaml

import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.jetbrains.rider.projectView.views.solutionExplorer.SolutionExplorerCustomization

class RobustSolutionExplorerProblems(project: Project) : SolutionExplorerCustomization(project) {
    override fun updateNode(presentation: PresentationData, file: VirtualFile) {
        if (!RobustYamlSettings.getInstance(project).state.highlightProblemFiles) return

        val problems = RobustProblemFiles.getInstance(project)
        problems.schedule()
        val severity = problems.severityOf(file) ?: return

        val fragments = presentation.coloredText.toList()
        presentation.clearText()
        if (fragments.isEmpty()) {
            presentation.addText(file.name, RobustProblemFiles.waved(SimpleTextAttributes.REGULAR_ATTRIBUTES, severity))
        } else {
            val first = fragments.first()
            presentation.addText(first.text, RobustProblemFiles.waved(first.attributes, severity))
            for (rest in fragments.drop(1)) presentation.addText(rest)
        }
        presentation.tooltip =
            if (severity == RobustProblemFiles.Severity.ERROR) ERROR_TOOLTIP else WARNING_TOOLTIP
    }

    private companion object {
        const val ERROR_TOOLTIP = "Unknown component name or parent prototype"
        const val WARNING_TOOLTIP = "Reference to a prototype id that does not exist"
    }
}
