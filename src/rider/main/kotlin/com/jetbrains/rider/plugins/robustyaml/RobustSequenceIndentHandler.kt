package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLSequence

private val logger = logger<RobustSequenceIndentHandler>()

private const val DASH = '-'
private const val COMPONENTS_KEY = "components"

class RobustSequenceIndentHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != DASH || !alignsSequences(project, file)) return Result.CONTINUE

        val document = editor.document
        val caret = editor.caretModel.offset
        val line = document.getLineNumber(caret)
        val lineStart = document.getLineStartOffset(line)

        val typed = document.charsSequence.subSequence(lineStart, caret)
        if (typed.isEmpty() || typed.last() != DASH) return Result.CONTINUE
        if (!typed.dropLast(1).all { it == ' ' }) return Result.CONTINUE

        val indent = typed.length - 1
        val owner = ownerLine(document, line) ?: return Result.CONTINUE
        val target = sequenceIndent(document, owner, line)
        if (indent <= target) return Result.CONTINUE

        PsiDocumentManager.getInstance(project).commitDocument(document)
        if (!inDeclaration(file, caret - 1)) return Result.CONTINUE

        logger.debug { "Dash moved from indent $indent to $target" }
        document.deleteString(lineStart, lineStart + indent - target)
        return Result.CONTINUE
    }
}

class RobustSequenceEnterHandler(private val original: EditorActionHandler) : EditorWriteActionHandler() {
    override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) {
        original.execute(editor, caret, dataContext)

        val project = editor.project ?: return
        if (DumbService.isDumb(project)) return

        val document = editor.document
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        if (!alignsSequences(project, file)) return

        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)

        val text = lineText(document, line).trim()
        if (text.isNotEmpty() && text != DASH.toString()) {
            logger.debug { "Enter: line $line is '$text', not empty and not a dash" }
            return
        }

        val owner = ownerLine(document, line)
        if (owner == null) {
            logger.debug { "Enter: no key line above $line" }
            return
        }

        PsiDocumentManager.getInstance(project).commitDocument(document)
        val keyValue = keyAt(file, document, owner)
        if (keyValue == null) {
            logger.debug { "Enter: no key at line $owner ('${lineText(document, owner)}')" }
            return
        }
        if (!isSequenceKey(keyValue)) {
            logger.debug { "Enter: key '${keyValue.keyText}' is not a sequence" }
            return
        }

        val target = sequenceIndent(document, owner, line)
        val item = " ".repeat(target) + "$DASH "
        document.replaceString(lineStart, lineEnd, item)
        editor.caretModel.moveToOffset(lineStart + item.length)
        logger.debug { "Enter under sequence key '${keyValue.keyText}', indent $target" }

        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || line >= document.lineCount) return@invokeLater

            val settled = lineText(document, line).toString()
            logger.debug { "After enter line $line is '$settled', expected '$item'" }
            if (settled == item) {
                editor.caretModel.moveToOffset(document.getLineStartOffset(line) + item.length)
                return@invokeLater
            }

            if (settled.trim() != DASH.toString()) return@invokeLater
            WriteCommandAction.runWriteCommandAction(project) {
                val start = document.getLineStartOffset(line)
                document.replaceString(start, document.getLineEndOffset(line), item)
                editor.caretModel.moveToOffset(start + item.length)
            }
        }
    }

    private fun keyAt(file: PsiFile, document: Document, line: Int): YAMLKeyValue? {
        val text = lineText(document, line)
        val colon = text.indexOfLast { it == ':' }
        if (colon < 0) return null

        val element = file.findElementAt(document.getLineStartOffset(line) + colon) ?: return null
        return PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
    }

    private fun isSequenceKey(keyValue: YAMLKeyValue): Boolean {
        when (keyValue.value) {
            null -> Unit
            is YAMLSequence -> return true
            else -> return false
        }
        if (isComponentsKey(keyValue)) return true
        return RobustValidation.field(keyValue)?.sequence == true
    }

    private fun isComponentsKey(keyValue: YAMLKeyValue): Boolean {
        if (keyValue.keyText != COMPONENTS_KEY) return false
        val declaration = RobustYamlContext.declarationAround(keyValue) ?: return false
        return !declaration.isComponent
    }
}

private fun alignsSequences(project: Project, file: PsiFile): Boolean =
    file is YAMLFile && RobustYamlSettings.getInstance(project).state.alignSequenceDash

private fun inDeclaration(file: PsiFile, offset: Int): Boolean {
    val element = file.findElementAt(offset) ?: return false
    return RobustYamlContext.declarationAround(element) != null
}

private fun ownerLine(document: Document, line: Int): Int? {
    for (candidate in line - 1 downTo 0) {
        val text = lineText(document, candidate)
        if (text.isBlank()) continue
        return candidate.takeIf { text.trimEnd().endsWith(':') }
    }
    return null
}

/**
 * Where the dash belongs: on the level of the items already written under the key, and only when
 * there are none — on the level of the key itself. A list written with the platform indent stays as
 * it is, so a single typed dash never splits an existing sequence in two.
 */
private fun sequenceIndent(document: Document, owner: Int, typed: Int): Int {
    val ownerIndent = indentOf(document, owner)
    for (candidate in owner + 1 until document.lineCount) {
        if (candidate == typed) continue
        val text = lineText(document, candidate)
        if (text.isBlank()) continue

        val indent = text.takeWhile { it == ' ' }.length
        val isItem = text.getOrNull(indent) == DASH
        logger.debug {
            "Sequence under line $owner (indent $ownerIndent): first item line $candidate " +
                "is '$text', indent $indent, item $isItem"
        }
        return if (isItem && indent >= ownerIndent) indent else ownerIndent
    }
    logger.debug { "Sequence under line $owner (indent $ownerIndent): no items yet" }
    return ownerIndent
}

private fun indentOf(document: Document, line: Int): Int =
    lineText(document, line).takeWhile { it == ' ' }.length

private fun lineText(document: Document, line: Int): CharSequence =
    document.charsSequence.subSequence(
        document.getLineStartOffset(line),
        document.getLineEndOffset(line),
    )
