package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

class RobustUnknownLocalizationIdInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                for (problem in RobustValidation.localizationIds(keyValue)) {
                    holder.registerProblem(
                        problem.element,
                        problem.message,
                        ProblemHighlightType.WARNING,
                        *problem.suggestions
                            .map { ChangeLocalizationIdFix(problem.element, it) }
                            .toTypedArray(),
                    )
                }
            }
        }
}
