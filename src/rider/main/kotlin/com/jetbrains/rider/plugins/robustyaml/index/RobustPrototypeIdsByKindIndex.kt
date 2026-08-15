package com.jetbrains.rider.plugins.robustyaml.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class RobustPrototypeIdsByKindIndex : FileBasedIndexExtension<String, String>() {
    override fun getName(): ID<String, String> = NAME

    override fun getVersion(): Int = 2

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension.equals("yml", ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, String, FileContent> =
        DataIndexer { content -> idsByKind(content.contentAsText) }

    companion object {
        val NAME: ID<String, String> = ID.create("robustyaml.prototype.ids.by.kind")

        const val ID_SEPARATOR = ';'

        fun idsByKind(text: CharSequence): Map<String, String> {
            val byKind = mutableMapOf<String, MutableSet<String>>()
            for ((id, value) in RobustPrototypeIdIndex.prototypeIds(text)) {
                for ((kind, _) in RobustPrototypeIdIndex.parseEntries(value)) {
                    if (kind.isEmpty()) continue
                    byKind.getOrPut(kind) { sortedSetOf() } += id
                }
            }
            return byKind.mapValues { (_, ids) -> ids.joinToString(ID_SEPARATOR.toString()) }
        }
    }
}
