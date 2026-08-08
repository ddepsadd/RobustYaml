package com.jetbrains.rider.plugins.robustyaml

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorIntegerDescriptor
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class RobustLocaleIndex : FileBasedIndexExtension<String, Int>() {
    override fun getName(): ID<String, Int> = NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<Int> = EnumeratorIntegerDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension.equals(EXTENSION, ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, Int, FileContent> =
        DataIndexer { content -> messages(content.contentAsText) }

    companion object {
        val NAME: ID<String, Int> = ID.create("robustyaml.locale.messages")

        const val EXTENSION = "ftl"

        private val MESSAGE = Regex("""(?m)^﻿?([A-Za-z][\w-]*)[ \t]*=""")

        fun messages(text: CharSequence): Map<String, Int> {
            val result = mutableMapOf<String, Int>()
            for (match in MESSAGE.findAll(text)) {
                val id = match.groups[1] ?: continue
                result.putIfAbsent(id.value, id.range.first)
            }
            return result
        }
    }
}
