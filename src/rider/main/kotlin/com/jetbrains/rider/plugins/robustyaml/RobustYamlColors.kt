package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object RobustYamlColors {
    val COMPONENT_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ROBUST_COMPONENT_NAME",
        DefaultLanguageHighlighterColors.INSTANCE_FIELD,
    )

    val PROTOTYPE_KIND: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ROBUST_PROTOTYPE_KIND",
        DefaultLanguageHighlighterColors.METADATA,
    )

    val RESOURCE_PATH: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ROBUST_RESOURCE_PATH",
        DefaultLanguageHighlighterColors.STRING,
    )
}
