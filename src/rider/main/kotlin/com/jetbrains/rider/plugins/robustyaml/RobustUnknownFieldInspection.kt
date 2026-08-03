package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

class RobustUnknownFieldInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                val problem = RobustValidation.unknownField(keyValue) ?: return
                val fixes = problem.suggestions
                    .map { ChangeFieldNameFix(keyValue, it) }
                    .toTypedArray()
                holder.registerProblem(
                    keyValue.key ?: keyValue,
                    problem.message,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    *fixes,
                )
            }
        }
}
