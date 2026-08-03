package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import org.jetbrains.yaml.YAMLSyntaxHighlighter
import javax.swing.Icon

class RobustYamlColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Robust YAML"

    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter = YAMLSyntaxHighlighter()

    override fun getDemoText(): String = """
        - type: <kind>entity</kind>
          id: <id>Flare</id>
          parent: <id>BaseItem</id>
          components:
          - type: <component>Sprite</component>
            sprite: <path>Objects/Misc/flare.rsi</path>
          - type: <component>ExpendableLight</component>
            glowDuration: 225
            litSound:
              path: <path>/Audio/Items/Flare/flare_on.ogg</path>
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "kind" to RobustYamlColors.PROTOTYPE_KIND,
        "component" to RobustYamlColors.COMPONENT_NAME,
        "id" to RobustYamlColors.PROTOTYPE_ID,
        "path" to RobustYamlColors.RESOURCE_PATH,
    )

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Prototype kind", RobustYamlColors.PROTOTYPE_KIND),
        AttributesDescriptor("Component name", RobustYamlColors.COMPONENT_NAME),
        AttributesDescriptor("Prototype id", RobustYamlColors.PROTOTYPE_ID),
        AttributesDescriptor("Resource path", RobustYamlColors.RESOURCE_PATH),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
