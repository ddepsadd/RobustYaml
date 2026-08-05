package model.rider

import com.jetbrains.rd.generator.nova.Ext
import com.jetbrains.rd.generator.nova.PredefinedType
import com.jetbrains.rd.generator.nova.call
import com.jetbrains.rd.generator.nova.field
import com.jetbrains.rd.generator.nova.immutableList
import com.jetbrains.rd.generator.nova.nullable
import com.jetbrains.rider.model.nova.ide.SolutionModel

@Suppress("unused")
object RobustYamlModel : Ext(SolutionModel.Solution) {
    private val RobustDataField = structdef {
        field("name", PredefinedType.string)
        field("type", PredefinedType.string)
        field("summary", PredefinedType.string.nullable)
        field("prototypeKind", PredefinedType.string.nullable)
    }

    private val RobustFieldQuery = structdef {
        field("className", PredefinedType.string)
        field("path", immutableList(PredefinedType.string))
    }

    init {
        call("typeFields", RobustFieldQuery, immutableList(RobustDataField))
    }
}
