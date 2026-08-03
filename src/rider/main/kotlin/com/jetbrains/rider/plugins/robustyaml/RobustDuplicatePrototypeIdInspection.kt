package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

class RobustDuplicatePrototypeIdInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                val message = RobustValidation.duplicateId(keyValue) ?: return
                holder.registerProblem(
                    keyValue.value ?: keyValue,
                    message,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                )
            }
        }
}
