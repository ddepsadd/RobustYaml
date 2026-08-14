package com.jetbrains.rider.plugins.robustyaml

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

class RobustYamlConfigurable(private val project: Project) : BoundConfigurable("Robust YAML") {
    private val state = RobustYamlSettings.getInstance(project).state

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Detect prototype directories automatically")
                .bindSelected(state::autoDetect)
                .comment("Looks for a Prototypes directory inside any top-level project directory.")
        }
        row {
            checkBox("Show prototypes in Solution Explorer")
                .bindSelected(state::showPrototypeRoot)
                .comment("Adds a Robust Prototypes root, so prototype files stay visible without Show All Files.")
        }
        row {
            checkBox("Highlight files with unknown components or ids")
                .bindSelected(state::highlightProblemFiles)
                .comment("Marks prototype files red in the tree when a component name or prototype id is not found.")
        }
        row {
            checkBox("Warn about localization messages nobody uses")
                .bindSelected(state::warnUnusedLocalization)
                .comment(
                    "Greys out a message in a .ftl file when no prototype, C# literal, XAML binding, " +
                        "guidebook or other message asks for it.",
                )
        }
        row {
            checkBox("Align sequence dash with its key")
                .bindSelected(state::alignSequenceDash)
                .comment(
                    "Typing '-' on a fresh line under a key moves it back to the key's own indent, " +
                        "the way prototypes are written.",
                )
        }
        row {
            textArea()
                .align(AlignX.FILL)
                .rows(6)
                .label("Additional prototype directories:", LabelPosition.TOP)
                .comment("One path per line. Relative paths are resolved against the project root.")
                .bindText(
                    { state.customRoots.joinToString("\n") },
                    { text ->
                        state.customRoots = text.lines()
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .toMutableList()
                    },
                )
        }
    }

    override fun apply() {
        val oldRoots = RobustPrototypeRootsProvider.indexedRoots(project)
        super.apply()
        RobustPrototypeRootsProvider.rootsChanged(project, oldRoots)
        ProjectView.getInstance(project).refresh()
    }
}
