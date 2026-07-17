package dev.kuml.desktop.ai

import dev.kuml.ai.tools.context.AnyKumlModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ScriptSerializerTest :
    FunSpec({

        test("emptyUml produces a classDiagram block without extra class names") {
            val model = AnyKumlModel.emptyUml("MyModel")
            val dsl = ScriptSerializer.toDsl(model)
            dsl shouldContain "classDiagram"
            dsl shouldNotContain "UmlClass"
            dsl shouldNotBe ""
        }

        test("C4 model produces TODO V3.0.26 comment") {
            val model = AnyKumlModel.emptyC4()
            val dsl = ScriptSerializer.toDsl(model)
            dsl shouldContain "TODO V3.0.26"
        }

        test("SysML2 model produces TODO V3.0.26 comment") {
            val model = AnyKumlModel.emptySysml2()
            val dsl = ScriptSerializer.toDsl(model)
            dsl shouldContain "TODO V3.0.26"
        }
    })
