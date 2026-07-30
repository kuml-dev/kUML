package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlToErmScriptTransformerTest :
    FunSpec({

        val transformer = UmlToErmScriptTransformer()

        test("wraps the ERM transform into exactly one GeneratedFile with a .erm.kuml.kts suffix") {
            val diagram =
                classDiagram(name = "Orders") {
                    classOf(name = "Customer") { attribute(name = "id", type = "UUID") }
                }
            val result = transformer.transform(source = diagram, ctx = TransformContext())
            result.shouldBeInstanceOf<TransformResult.Success<*>>()
            val files = (result as TransformResult.Success).output
            files shouldHaveSize 1
            files.single().relativePath shouldEndWith ".erm.kuml.kts"
            files.single().content shouldContain "ermModel(name = \"Orders\")"
        }

        test("propagates a Failure from the underlying UmlToErmTransformer unchanged") {
            val diagram =
                classDiagram(name = "Unsafe") {
                    classOf(name = """Bad Name;""") { attribute(name = "id", type = "UUID") }
                }
            val result = transformer.transform(source = diagram, ctx = TransformContext())
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }
    })
