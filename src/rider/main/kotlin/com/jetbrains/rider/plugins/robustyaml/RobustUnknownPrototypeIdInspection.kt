package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

class RobustUnknownPrototypeIdInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitScalar(scalar: YAMLScalar) {
                val message = RobustValidation.unknownPrototypeId(scalar) ?: return
                holder.registerProblem(scalar, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
            }

            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                for (problem in RobustValidation.prototypeIdValues(keyValue)) {
                    val fixes = problem.suggestions
                        .map { ChangePrototypeIdFix(problem.element, it) as LocalQuickFix }
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
