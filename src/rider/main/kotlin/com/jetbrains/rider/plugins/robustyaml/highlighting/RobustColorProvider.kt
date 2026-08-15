package com.jetbrains.rider.plugins.robustyaml.highlighting

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLScalar
import java.awt.Color

class RobustColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        val scalar = element as? YAMLScalar ?: return null
        return parse(scalar.textValue)
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        val scalar = element as? YAMLScalar ?: return
        val text = scalar.textValue
        if (parse(text) == null) return
        scalar.updateText(format(color, text))
    }

    private fun parse(text: String): Color? {
        if (text.firstOrNull() != '#') return null
        val digits = text.substring(1)
        if (digits.length !in DIGIT_COUNTS) return null
        if (digits.any { Character.digit(it, 16) < 0 }) return null

        val wide = digits.length > 4
        val channels = IntArray(if (wide) digits.length / 2 else digits.length) { index ->
            if (wide) digits.substring(index * 2, index * 2 + 2).toInt(16)
            else Character.digit(digits[index], 16) * 17
        }
        return Color(channels[0], channels[1], channels[2], channels.getOrElse(3) { 255 })
    }

    private fun format(color: Color, old: String): String {
        val pattern = if (old.any { it in 'a'..'f' }) "%02x" else "%02X"
        val keepAlpha = color.alpha != 255 || old.length == 5 || old.length == 9
        return buildString {
            append('#')
            append(pattern.format(color.red))
            append(pattern.format(color.green))
            append(pattern.format(color.blue))
            if (keepAlpha) append(pattern.format(color.alpha))
        }
    }

    private companion object {
        val DIGIT_COUNTS = setOf(3, 4, 6, 8)
    }
}
