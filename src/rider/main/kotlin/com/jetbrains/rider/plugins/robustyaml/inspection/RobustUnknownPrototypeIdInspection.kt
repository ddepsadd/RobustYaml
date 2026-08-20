package com.jetbrains.rider.plugins.robustyaml.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.rider.plugins.robustyaml.quickfix.ChangePrototypeIdFix
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

class RobustUnknownPrototypeIdInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitScalar(scalar: YAMLScalar) {
                val problem = RobustValidation.unknownPrototypeId(scalar) ?: return
                val fixes = problem.suggestions
                    .mapIndexed { rank, it -> ChangePrototypeIdFix(scalar, it, rank) as LocalQuickFix }
                    .toTypedArray()
                holder.registerProblem(scalar, problem.message, highlightOf(problem.critical), *fixes)
            }

            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                for (problem in RobustValidation.prototypeIdValues(keyValue)) {
                    val fixes = problem.suggestions
                        .mapIndexed { rank, it -> ChangePrototypeIdFix(problem.element, it, rank) as LocalQuickFix }
                        .toTypedArray()
                    holder.registerProblem(
                        problem.element,
                        problem.message,
                        highlightOf(problem.critical),
                        *fixes,
                    )
                }
            }

            private fun highlightOf(critical: Boolean): ProblemHighlightType =
                if (critical) ProblemHighlightType.GENERIC_ERROR else ProblemHighlightType.WARNING
        }
}
