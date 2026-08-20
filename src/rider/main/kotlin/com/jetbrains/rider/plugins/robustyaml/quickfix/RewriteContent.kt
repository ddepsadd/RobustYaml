package com.jetbrains.rider.plugins.robustyaml.quickfix

import com.intellij.openapi.editor.Editor
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement

/**
 * Where the caret has to land once a fix has rewritten the text it stands on.
 *
 * The platform will not put it there. `DocumentImpl.replaceString` trims the common prefix and the
 * common suffix before writing (two character loops around `subSequence` in its bytecode), so
 * `WallLaye` → `WallLayer` reaches the document not as a replacement of eight characters but as an
 * insertion of a single `r` exactly at the caret. And `CaretImpl$PositionMarker.changedUpdateImpl`
 * steps over an insertion at its own offset only when `needToShiftWhiteSpaces` holds — the
 * character to the left is whitespace, the inserted fragment is whitespace and carries no line
 * break. A letter is none of that, so the caret stays where it was and the user is left standing
 * inside the word: `WallLaye|r`. Typing does not suffer from this only because there the caret is
 * moved by the typing action itself, not by the document.
 *
 * The offset is computed before the write rather than from the element afterwards: the start of the
 * content does not move — that is exactly the prefix the document just trimmed — while
 * `element.textRange.endOffset` would land after the closing quote of a quoted scalar.
 */
internal fun rewriteContent(element: PsiElement, text: String, editor: Editor?) {
    val manipulator = ElementManipulators.getManipulator(element) ?: return
    val start = element.textRange.startOffset + manipulator.getRangeInElement(element).startOffset
    manipulator.handleContentChange(element, text)
    editor?.caretModel?.moveToOffset(start + text.length)
}

/**
 * The same offset for a fix that writes some other way. For `YAMLKeyValue` the manipulator is
 * `YAMLKeyValueKeyManipulator` and its content is the raw text of the **key** — quotes stripped —
 * so this is the start of the key, not of the value.
 */
internal fun contentStart(element: PsiElement): Int =
    element.textRange.startOffset +
        (ElementManipulators.getManipulator(element)?.getRangeInElement(element)?.startOffset ?: 0)
