package com.jetbrains.rider.plugins.robustyaml.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

object RobustYamlColors {
    val COMPONENT_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ROBUST_COMPONENT_NAME",
        DefaultLanguageHighlighterColors.INSTANCE_FIELD,
    )

    val PROTOTYPE_KIND: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ROBUST_PROTOTYPE_KIND",
        DefaultLanguageHighlighterColors.METADATA,
    )

    val PROTOTYPE_ID: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ROBUST_PROTOTYPE_ID",
        DefaultLanguageHighlighterColors.GLOBAL_VARIABLE,
    )

    val RESOURCE_PATH: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ROBUST_RESOURCE_PATH",
        DefaultLanguageHighlighterColors.STRING,
    )

    fun waveColor(error: Boolean): Color {
        val key = if (error) CodeInsightColors.ERRORS_ATTRIBUTES else CodeInsightColors.WARNINGS_ATTRIBUTES
        val source = EditorColorsManager.getInstance().globalScheme.getAttributes(key, true)
        return source?.effectColor
            ?: source?.errorStripeColor
            ?: source?.backgroundColor
            ?: if (error) JBColor.RED else JBColor.ORANGE
    }

    fun waveAttributes(error: Boolean): TextAttributes =
        TextAttributes(null, null, waveColor(error), EffectType.WAVE_UNDERSCORE, Font.PLAIN)
}
