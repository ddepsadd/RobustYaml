package com.jetbrains.rider.plugins.robustyaml.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class RobustPrototypeKindIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension.equals("cs", ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, Void, FileContent> =
        DataIndexer { content -> prototypeKinds(content.contentAsText).associateWith { null } }

    companion object {
        val NAME: ID<String, Void> = ID.create("robustyaml.prototype.kinds")

        private const val MARKER = "[Prototype"

        private val ATTRIBUTE = Regex("""\[Prototype(?:\(([^)]*)\))?[,\]]""")
        private val CLASS_NAME = Regex("""class\s+(\w+)""")
        private val STRING_LITERAL = Regex(""""([^"]+)"""")

        fun prototypeKinds(text: CharSequence): Set<String> {
            if (!text.contains(MARKER)) return emptySet()

            val kinds = mutableSetOf<String>()
            for (attribute in ATTRIBUTE.findAll(text)) {
                val literal = STRING_LITERAL.find(attribute.groupValues[1])?.groupValues?.get(1)
                if (literal != null) {
                    kinds += literal
                    continue
                }
                val className =
                    CLASS_NAME.find(text, attribute.range.last + 1)?.groupValues?.get(1) ?: continue
                kinds += className.removeSuffix("Prototype").replaceFirstChar { it.lowercase() }
            }
            return kinds
        }
    }
}
