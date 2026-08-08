package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

class RobustMissingRequiredFieldInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                val missing = RobustRequiredFields.missing(keyValue)
                if (missing.isEmpty()) return

                holder.registerProblem(
                    keyValue.value ?: keyValue,
                    RobustRequiredFields.message(keyValue, missing),
                    ProblemHighlightType.GENERIC_ERROR,
                    AddRequiredFieldsFix(keyValue, missing),
                )
            }
        }
}
