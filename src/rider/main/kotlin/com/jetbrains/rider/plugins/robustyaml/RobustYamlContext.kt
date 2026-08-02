package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

object RobustYamlContext {
    fun isPrototypeKindKey(keyValue: YAMLKeyValue): Boolean =
        keyValue.keyText == "type" &&
            PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java, true) == null

    fun isComponentTypeKey(keyValue: YAMLKeyValue): Boolean {
        if (keyValue.keyText != "type") return false
        val enclosing =
            PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java, true) ?: return false
        if (enclosing.keyText != "components") return false
        val entityMapping =
            PsiTreeUtil.getParentOfType(enclosing, YAMLMapping::class.java, true) ?: return false
        return entityMapping.getKeyValueByKey("type")?.valueText == "entity"
    }
}
