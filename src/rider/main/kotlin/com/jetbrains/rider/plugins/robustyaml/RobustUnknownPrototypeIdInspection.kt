package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

class RobustUnknownPrototypeIdInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitScalar(scalar: YAMLScalar) {
                val message = RobustValidation.unknownPrototypeId(scalar) ?: return
                holder.registerProblem(scalar, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
            }
        }
}
