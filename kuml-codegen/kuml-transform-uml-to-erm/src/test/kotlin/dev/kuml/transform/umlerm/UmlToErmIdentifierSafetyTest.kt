package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlToErmIdentifierSafetyTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        test("a class name containing SQL metacharacters fails the transform instead of being silently mangled") {
            val diagram =
                classDiagram("Unsafe") {
                    classOf("""Bad Name; DROP TABLE x;--""") {
                        attribute("id", "UUID")
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            result.shouldBeInstanceOf<TransformResult.Failure>()
            result.errors.shouldNotBeEmpty()
            result.errors.first().message shouldContain "not a safe SQL identifier"
        }

        test("an attribute name containing SQL metacharacters fails the transform") {
            val diagram =
                classDiagram("Unsafe") {
                    classOf("Customer") {
                        attribute("id", "UUID")
                        attribute("""bad col;name""", "String")
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }
    })
