package com.jetbrains.rider.plugins.robustyaml.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.rider.plugins.robustyaml.quickfix.ChangeEnumValueFix
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

class RobustInvalidEnumValueInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                for (problem in RobustValidation.enumValues(keyValue)) {
                    val fixes = problem.suggestions
                        .mapIndexed { rank, it -> ChangeEnumValueFix(problem.element, it, rank) as LocalQuickFix }
                        .toTypedArray()
                    holder.registerProblem(
                        problem.element,
                        problem.message,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        *fixes,
                    )
                }
            }
        }
}
