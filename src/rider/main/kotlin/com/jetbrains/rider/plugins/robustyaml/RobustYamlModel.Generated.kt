@file:Suppress("EXPERIMENTAL_API_USAGE","EXPERIMENTAL_UNSIGNED_LITERALS","PackageDirectoryMismatch","UnusedImport","unused","LocalVariableName","CanBeVal","PropertyName","EnumEntryName","ClassName","ObjectPropertyName","UnnecessaryVariable","SpellCheckingInspection")
package com.jetbrains.rd.ide.model

import com.jetbrains.rd.framework.*
import com.jetbrains.rd.framework.base.*
import com.jetbrains.rd.framework.impl.*

import com.jetbrains.rd.util.lifetime.*
import com.jetbrains.rd.util.reactive.*
import com.jetbrains.rd.util.string.*
import com.jetbrains.rd.util.*
import kotlin.time.Duration
import kotlin.reflect.KClass
import kotlin.jvm.JvmStatic



/**
 * #### Generated from [RobustYamlModel.kt:11]
 */
class RobustYamlModel private constructor(
    private val _typeFields: RdCall<RobustFieldQuery, RobustFieldsReply>
) : RdExtBase() {
    //companion
    
    companion object : ISerializersOwner {
        
        override fun registerSerializersCore(serializers: ISerializers)  {
            val classLoader = javaClass.classLoader
            serializers.register(LazyCompanionMarshaller(RdId(1879621150234290220), classLoader, "com.jetbrains.rd.ide.model.RobustDataField"))
            serializers.register(LazyCompanionMarshaller(RdId(2928082736417472178), classLoader, "com.jetbrains.rd.ide.model.RobustFieldQuery"))
            serializers.register(LazyCompanionMarshaller(RdId(-1463155538665054867), classLoader, "com.jetbrains.rd.ide.model.RobustFieldsReply"))
        }
        
        
        
        
        
        const val serializationHash = -2428411156860904630L
        
    }
    override val serializersOwner: ISerializersOwner get() = RobustYamlModel
    override val serializationHash: Long get() = RobustYamlModel.serializationHash
    
    //fields
    val typeFields: IRdCall<RobustFieldQuery, RobustFieldsReply> get() = _typeFields
    //methods
    //initializer
    init {
        bindableChildren.add("typeFields" to _typeFields)
    }
    
    //secondary constructor
    internal constructor(
    ) : this(
        RdCall<RobustFieldQuery, RobustFieldsReply>(RobustFieldQuery, RobustFieldsReply)
    )
    
    //equals trait
    //hash code trait
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RobustYamlModel (")
        printer.indent {
            print("typeFields = "); _typeFields.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    override fun deepClone(): RobustYamlModel   {
        return RobustYamlModel(
            _typeFields.deepClonePolymorphic()
        )
    }
    //contexts
    //threading
    override val extThreading: ExtThreadingKind get() = ExtThreadingKind.Default
}
val Solution.robustYamlModel get() = getOrCreateExtension("robustYamlModel", ::RobustYamlModel)



/**
 * #### Generated from [RobustYamlModel.kt:13]
 */
data class RobustDataField (
    val name: String,
    val type: String,
    val summary: String?,
    val prototypeKind: String?,
    val keyPrototypeKind: String?,
    val dictionary: Boolean,
    val sequence: Boolean,
    val customSerializer: Boolean,
    val localized: Boolean,
    val values: List<String>,
    val keyValues: List<String>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RobustDataField> {
        override val _type: KClass<RobustDataField> = RobustDataField::class
        override val id: RdId get() = RdId(1879621150234290220)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RobustDataField  {
            val name = buffer.readString()
            val type = buffer.readString()
            val summary = buffer.readNullable { buffer.readString() }
            val prototypeKind = buffer.readNullable { buffer.readString() }
            val keyPrototypeKind = buffer.readNullable { buffer.readString() }
            val dictionary = buffer.readBool()
            val sequence = buffer.readBool()
            val customSerializer = buffer.readBool()
            val localized = buffer.readBool()
            val values = buffer.readList { buffer.readString() }
            val keyValues = buffer.readList { buffer.readString() }
            return RobustDataField(name, type, summary, prototypeKind, keyPrototypeKind, dictionary, sequence, customSerializer, localized, values, keyValues)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RobustDataField)  {
            buffer.writeString(value.name)
            buffer.writeString(value.type)
            buffer.writeNullable(value.summary) { buffer.writeString(it) }
            buffer.writeNullable(value.prototypeKind) { buffer.writeString(it) }
            buffer.writeNullable(value.keyPrototypeKind) { buffer.writeString(it) }
            buffer.writeBool(value.dictionary)
            buffer.writeBool(value.sequence)
            buffer.writeBool(value.customSerializer)
            buffer.writeBool(value.localized)
            buffer.writeList(value.values) { v -> buffer.writeString(v) }
            buffer.writeList(value.keyValues) { v -> buffer.writeString(v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RobustDataField
        
        if (name != other.name) return false
        if (type != other.type) return false
        if (summary != other.summary) return false
        if (prototypeKind != other.prototypeKind) return false
        if (keyPrototypeKind != other.keyPrototypeKind) return false
        if (dictionary != other.dictionary) return false
        if (sequence != other.sequence) return false
        if (customSerializer != other.customSerializer) return false
        if (localized != other.localized) return false
        if (values != other.values) return false
        if (keyValues != other.keyValues) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + name.hashCode()
        __r = __r*31 + type.hashCode()
        __r = __r*31 + if (summary != null) summary.hashCode() else 0
        __r = __r*31 + if (prototypeKind != null) prototypeKind.hashCode() else 0
        __r = __r*31 + if (keyPrototypeKind != null) keyPrototypeKind.hashCode() else 0
        __r = __r*31 + dictionary.hashCode()
        __r = __r*31 + sequence.hashCode()
        __r = __r*31 + customSerializer.hashCode()
        __r = __r*31 + localized.hashCode()
        __r = __r*31 + values.hashCode()
        __r = __r*31 + keyValues.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RobustDataField (")
        printer.indent {
            print("name = "); name.print(printer); println()
            print("type = "); type.print(printer); println()
            print("summary = "); summary.print(printer); println()
            print("prototypeKind = "); prototypeKind.print(printer); println()
            print("keyPrototypeKind = "); keyPrototypeKind.print(printer); println()
            print("dictionary = "); dictionary.print(printer); println()
            print("sequence = "); sequence.print(printer); println()
            print("customSerializer = "); customSerializer.print(printer); println()
            print("localized = "); localized.print(printer); println()
            print("values = "); values.print(printer); println()
            print("keyValues = "); keyValues.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [RobustYamlModel.kt:27]
 */
data class RobustFieldQuery (
    val className: String,
    val path: List<String>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RobustFieldQuery> {
        override val _type: KClass<RobustFieldQuery> = RobustFieldQuery::class
        override val id: RdId get() = RdId(2928082736417472178)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RobustFieldQuery  {
            val className = buffer.readString()
            val path = buffer.readList { buffer.readString() }
            return RobustFieldQuery(className, path)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RobustFieldQuery)  {
            buffer.writeString(value.className)
            buffer.writeList(value.path) { v -> buffer.writeString(v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RobustFieldQuery
        
        if (className != other.className) return false
        if (path != other.path) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + className.hashCode()
        __r = __r*31 + path.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RobustFieldQuery (")
        printer.indent {
            print("className = "); className.print(printer); println()
            print("path = "); path.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [RobustYamlModel.kt:32]
 */
data class RobustFieldsReply (
    val resolved: Boolean,
    val fields: List<RobustDataField>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RobustFieldsReply> {
        override val _type: KClass<RobustFieldsReply> = RobustFieldsReply::class
        override val id: RdId get() = RdId(-1463155538665054867)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RobustFieldsReply  {
            val resolved = buffer.readBool()
            val fields = buffer.readList { RobustDataField.read(ctx, buffer) }
            return RobustFieldsReply(resolved, fields)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RobustFieldsReply)  {
            buffer.writeBool(value.resolved)
            buffer.writeList(value.fields) { v -> RobustDataField.write(ctx, buffer, v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RobustFieldsReply
        
        if (resolved != other.resolved) return false
        if (fields != other.fields) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + resolved.hashCode()
        __r = __r*31 + fields.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RobustFieldsReply (")
        printer.indent {
            print("resolved = "); resolved.print(printer); println()
            print("fields = "); fields.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}
