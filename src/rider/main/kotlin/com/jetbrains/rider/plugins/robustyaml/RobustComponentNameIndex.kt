package com.jetbrains.rider.plugins.robustyaml

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class RobustComponentNameIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getVersion(): Int = 3

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension.equals("cs", ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, Void, FileContent> =
        DataIndexer { content -> componentNames(content.contentAsText).associateWith { null } }

    companion object {
        val NAME: ID<String, Void> = ID.create("robustyaml.component.names")

        private const val REGISTER_MARKER = "RegisterComponent"

        private val PROTO_NAME = Regex("""ComponentProtoName\(\s*"([^"]+)"\s*\)""")
        private val COMPONENT_CLASS =
            Regex("""((?:(?:public|internal|sealed|partial|abstract|static)\s+)*)class\s+(\w+)Component\b""")

        fun componentNames(text: CharSequence): Set<String> {
            val names = mutableSetOf<String>()
            PROTO_NAME.findAll(text).forEach { names += it.groupValues[1] }
            if (text.contains(REGISTER_MARKER)) {
                for (match in COMPONENT_CLASS.findAll(text)) {
                    if (!match.groupValues[1].contains("abstract")) names += match.groupValues[2]
                }
            }
            return names
        }
    }
}
