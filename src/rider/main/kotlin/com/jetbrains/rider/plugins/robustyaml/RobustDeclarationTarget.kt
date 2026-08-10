package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

/**
 * Navigation into a `.cs` file that lands on the type rather than on its first line. C# has no
 * parser on the frontend, so there is no PSI to resolve into and the file itself would open at
 * offset zero — on the `namespace` line, several screens away from the class that was clicked.
 */
object RobustDeclarationTarget {
    private val DECLARATION =
        Regex("""\b(?:class|record|struct|interface|enum)\s+(\w+)""")

    fun of(file: PsiFile, name: String): PsiElement {
        val offset = declarationOffset(file, name) ?: return file
        return DeclarationInFile(file, name, offset)
    }

    private fun declarationOffset(file: PsiFile, name: String): Int? =
        DECLARATION.findAll(file.text)
            .firstOrNull { it.groupValues[1] == name }
            ?.groups?.get(1)?.range?.first
}

private class DeclarationInFile(
    private val file: PsiFile,
    private val name: String,
    private val offset: Int,
) : FakePsiElement() {
    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getName(): String = name

    override fun getPresentableText(): String = name

    override fun getTextOffset(): Int = offset

    override fun getTextRange(): TextRange = TextRange(offset, offset + name.length)

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
    }
}
