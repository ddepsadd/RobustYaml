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
    private val _typeFields: RdCall<RobustFieldQuery, List<RobustDataField>>
) : RdExtBase() {
    //companion
    
    companion object : ISerializersOwner {
        
        override fun registerSerializersCore(serializers: ISerializers)  {
            val classLoader = javaClass.classLoader
            serializers.register(LazyCompanionMarshaller(RdId(1879621150234290220), classLoader, "com.jetbrains.rd.ide.model.RobustDataField"))
            serializers.register(LazyCompanionMarshaller(RdId(2928082736417472178), classLoader, "com.jetbrains.rd.ide.model.RobustFieldQuery"))
        }
        
        
        
        
        private val __RobustDataFieldListSerializer = RobustDataField.list()
        
        const val serializationHash = -5474492741890904948L
        
    }
    override val serializersOwner: ISerializersOwner get() = RobustYamlModel
    override val serializationHash: Long get() = RobustYamlModel.serializationHash
    
    //fields
    val typeFields: IRdCall<RobustFieldQuery, List<RobustDataField>> get() = _typeFields
    //methods
    //initializer
    init {
        bindableChildren.add("typeFields" to _typeFields)
    }
    
    //secondary constructor
    internal constructor(
    ) : this(
        RdCall<RobustFieldQuery, List<RobustDataField>>(RobustFieldQuery, __RobustDataFieldListSerializer)
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
    val prototypeKind: String?
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
            return RobustDataField(name, type, summary, prototypeKind)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RobustDataField)  {
            buffer.writeString(value.name)
            buffer.writeString(value.type)
            buffer.writeNullable(value.summary) { buffer.writeString(it) }
            buffer.writeNullable(value.prototypeKind) { buffer.writeString(it) }
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
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + name.hashCode()
        __r = __r*31 + type.hashCode()
        __r = __r*31 + if (summary != null) summary.hashCode() else 0
        __r = __r*31 + if (prototypeKind != null) prototypeKind.hashCode() else 0
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
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [RobustYamlModel.kt:20]
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
