package com.jetbrains.rider.plugins.robustyaml.editor

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Macro
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TextResult

/**
 * The `- ` a template writes in front of a declaration, but only where there is not one already.
 *
 * Every skeleton this plugin ships starts an item of a sequence, and the dash is part of it: typed
 * on an empty line, `comp` has to produce `- type: X`. The trouble is that the line is often not
 * empty. Enter under `components:` already leaves `- ` behind — that is what
 * [RobustSequenceEnterHandler] is for — and expanding the skeleton there wrote the dash a second
 * time, for `- - type:`. Broken YAML, and produced by two features of this plugin agreeing with
 * each other.
 *
 * A live template cannot say "this part only sometimes", so the dash is a variable and this macro
 * is what computes it. The question is asked of the text of the line to the left of the template,
 * not of the PSI: at expansion time the abbreviation has just been removed and nothing has been
 * inserted yet, so there is no tree to ask, while the characters are exactly what decides.
 */
class RobustSequenceDashMacro : Macro() {
    override fun getName(): String = NAME

    override fun getPresentableName(): String = "$NAME()"

    override fun calculateResult(params: Array<Expression>, context: ExpressionContext): Result {
        val document = context.editor?.document ?: return TextResult(DASH)
        val start = context.templateStartOffset
        if (start < 0 || start > document.textLength) return TextResult(DASH)

        val lineStart = document.getLineStartOffset(document.getLineNumber(start))
        val prefix = document.charsSequence.subSequence(lineStart, start)
        return TextResult(if (dashNeeded(prefix)) DASH else "")
    }

    companion object {
        const val NAME = "robustSequenceDash"
        private const val DASH = "- "

        /**
         * Whether the text of the line before the template still owes a dash. Read backwards over
         * blanks: a dash behind them means the item is already open. Anything else means the line
         * holds something of its own, and a skeleton written there is wrong however it starts — the
         * dash is kept so that at least the shape of the mistake is the familiar one.
         */
        internal fun dashNeeded(prefix: CharSequence): Boolean {
            for (i in prefix.length - 1 downTo 0) {
                val symbol = prefix[i]
                if (symbol == ' ' || symbol == '\t') continue
                return symbol != '-'
            }
            return true
        }
    }
}
