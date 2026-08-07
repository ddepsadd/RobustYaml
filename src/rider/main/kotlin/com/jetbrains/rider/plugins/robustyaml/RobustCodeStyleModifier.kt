package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier
import com.intellij.psi.codeStyle.modifier.CodeStyleStatusBarUIContributor
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings
import org.jetbrains.yaml.formatter.YAMLCodeStyleSettings
import org.jetbrains.yaml.psi.YAMLFile

class RobustCodeStyleModifier : CodeStyleSettingsModifier {
    override fun modifySettings(settings: TransientCodeStyleSettings, file: PsiFile): Boolean {
        if (file !is YAMLFile) return false
        if (!RobustYamlSettings.getInstance(file.project).state.alignSequenceDash) return false
        if (!isPrototypeFile(file)) return false

        val yaml = settings.getCustomSettings(YAMLCodeStyleSettings::class.java)
        if (!yaml.INDENT_SEQUENCE_VALUE) return false

        logger.debug { "Sequences in ${file.name} are indented at their key" }
        yaml.INDENT_SEQUENCE_VALUE = false
        return true
    }

    override fun getName(): String = "Robust YAML"

    override fun getStatusBarUiContributor(
        settings: TransientCodeStyleSettings,
    ): CodeStyleStatusBarUIContributor? = null

    private fun isPrototypeFile(file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        return generateSequence(virtualFile.parent) { it.parent }
            .any { it.name.endsWith(PROTOTYPES_DIRECTORY) }
    }

    private companion object {
        const val PROTOTYPES_DIRECTORY = "Prototypes"

        val logger = logger<RobustCodeStyleModifier>()
    }
}
