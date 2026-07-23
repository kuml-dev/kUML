package dev.kuml.core.script.print

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.InProcessScriptEvaluator
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlConstraintKind
import dev.kuml.uml.Visibility
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.comment
import dev.kuml.uml.dsl.constraint
import dev.kuml.uml.dsl.dependency
import dev.kuml.uml.dsl.enumOf
import dev.kuml.uml.dsl.generalization
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.literal
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.print.UmlModelDslPrinter
import dev.kuml.uml.dsl.realization
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Full-equality round-trip tests for [UmlModelDslPrinter]: build a
 * [KumlDiagram] directly via the `classDiagram { … }` DSL, print it, re-parse
 * the printed script through the **compiler** path
 * ([InProcessScriptEvaluator] → `KumlScriptHost`), and assert the re-parsed
 * [KumlDiagram] is fully data-class-equal to the original.
 *
 * This lives in `kuml-core-script` (not `kuml-core-dsl`, where
 * [UmlModelDslPrinter] itself lives) because the compiler-path oracle
 * ([InProcessScriptEvaluator]) is only available here — `kuml-core-script`
 * depends on `kuml-core-dsl`, and a dependency the other way around would be
 * a forbidden Gradle project cycle.
 *
 * The compiler dialect is the printer's documented contract (see
 * [UmlModelDslPrinter]'s KDoc) — these tests deliberately do **not** exercise
 * `InterpreterScriptEvaluator`, which only accepts a stricter subset (no
 * string-ID relationships, no constraint `kind`/`contextOperation`, no
 * parameter `direction`/`defaultValue`, no multi-anchor comments).
 *
 * ## Element-ordering discipline
 *
 * [UmlModelDslPrinter] re-emits `diagram.elements` grouped by kind, in a
 * fixed canonical order: enumerations, interfaces, classes, generalizations,
 * realizations, associations, dependencies, comments (with their anchor
 * links). Because `KumlDiagram.elements` is a flat, order-sensitive list
 * containing every element regardless of kind, a re-parsed diagram's
 * `elements` list follows that same canonical order (it is simply the order
 * the printed script's top-level statements run in). For full data-class
 * equality to hold, every fixture below therefore declares its DSL calls in
 * that exact same canonical order at the top level: all classifiers first,
 * then generalization(s), then realization(s), then association(s), then
 * dependency(ies), then comment(s). (Ordering *within* one classifier's body
 * — attributes vs. operations vs. constraints — does not matter: those are
 * separate list fields on [dev.kuml.uml.UmlClass]/[dev.kuml.uml.UmlInterface],
 * not part of the flat top-level `elements` list.)
 */
class PrinterRoundTripTest :
    StringSpec({

        fun reparse(printed: String): KumlDiagram {
            val result = InProcessScriptEvaluator.evaluate(printed, "roundtrip.kuml.kts")
            require(result is EvaluatedScript.Success) { "re-parse failed: $result" }
            val extracted = result.diagram
            require(extracted is ExtractedDiagram.Uml) { "expected a UML diagram, got: $extracted" }
            return extracted.diagram
        }

        "dependency (with and without name) round-trips" {
            val original =
                classDiagram("D") {
                    val a = classOf("Order")
                    val b = classOf("NotificationService")
                    val c = classOf("AuditLog")
                    dependency(client = a, supplier = b, name = "notifies")
                    dependency(client = a, supplier = c)
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "invariant and precondition constraints round-trip" {
            val original =
                classDiagram("D") {
                    classOf("Order") {
                        attribute(name = "total", type = "Int")
                        operation(name = "place")
                        constraint(name = "hasTotal", body = "self.total >= 0")
                        constraint(
                            name = "PlacePre",
                            body = "self.total > 0",
                            kind = UmlConstraintKind.Precondition,
                            contextOperation = "place",
                        )
                    }
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "comments (zero-anchor and multi-anchor) round-trip" {
            val original =
                classDiagram("D") {
                    val order = classOf("Order")
                    val item = classOf("OrderItem")
                    comment(text = "General remark, not attached to anything.")
                    comment(text = "Applies to both.", order, item)
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "association name, aggregation, role and navigable round-trip" {
            val original =
                classDiagram("D") {
                    val customer = classOf("Customer")
                    val order = classOf("Order")
                    association(source = customer, target = order) {
                        name = "places"
                        aggregation = AggregationKind.COMPOSITE
                        source { navigable = false }
                        target {
                            multiplicity("0..*")
                            role = "orders"
                        }
                    }
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "association stereotypes round-trip and force block form" {
            val original =
                classDiagram("D") {
                    val customer = classOf("Customer")
                    val order = classOf("Order")
                    association(source = customer, target = order) {
                        stereotypes += "FK"
                    }
                }

            val printed = UmlModelDslPrinter.print(original)
            printed shouldContain "stereotypes += \"FK\""
            reparse(printed) shouldBe original
        }

        "operation parameters (IN, OUT, defaultValue) round-trip in declared order" {
            val original =
                classDiagram("D") {
                    classOf("Order") {
                        operation(name = "place") {
                            parameter(name = "items", type = "List<OrderItem>")
                            parameter(name = "flags", type = "Int", direction = ParameterDirection.OUT, defaultValue = "0")
                            returns("OrderId")
                        }
                    }
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "non-default visibility on class, operation and attribute round-trips" {
            val original =
                classDiagram("D") {
                    classOf("Order") {
                        visibility = Visibility.PROTECTED
                        attribute(name = "id", type = "UUID", visibility = Visibility.PUBLIC)
                        operation(name = "internalHelper") {
                            visibility = Visibility.PRIVATE
                        }
                    }
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "classifier-typed attribute (referencedId) round-trips via typeRef(name, referencedId)" {
            val original =
                classDiagram("D") {
                    val status =
                        enumOf("OrderStatus") {
                            literal("DRAFT")
                            literal("CONFIRMED")
                        }
                    classOf("Order") {
                        attribute(name = "status", type = status)
                    }
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "comprehensive: every gap combined into a single diagram round-trips exactly" {
            val original =
                classDiagram("Order Domain") {
                    val status =
                        enumOf("OrderStatus") {
                            literal("DRAFT")
                            literal("PAID")
                        }
                    val greeter = interfaceOf("Greeter") { operation(name = "greet") }
                    val greeterImpl = classOf("GreeterImpl")
                    val customer =
                        classOf("Customer") {
                            attribute(name = "id", type = "UUID", visibility = Visibility.PUBLIC)
                        }
                    val order =
                        classOf("Order") {
                            visibility = Visibility.PROTECTED
                            attribute(name = "status", type = status)
                            attribute(name = "total", type = "Int")
                            operation(name = "place") {
                                parameter(name = "items", type = "List<String>")
                                parameter(
                                    name = "flags",
                                    type = "Int",
                                    direction = ParameterDirection.OUT,
                                    defaultValue = "0",
                                )
                                returns("OrderId")
                            }
                            operation(name = "cancel") {
                                visibility = Visibility.PRIVATE
                                isStatic = true
                            }
                            constraint(name = "hasTotal", body = "self.total >= 0")
                            constraint(
                                name = "PlacePre",
                                body = "self.total > 0",
                                kind = UmlConstraintKind.Precondition,
                                contextOperation = "place",
                            )
                        }
                    val notifier = classOf("NotificationService")
                    val auditLog = classOf("AuditLog")

                    // NOTE: UmlModelDslPrinter always emits generalizations before
                    // realizations (its fixed canonical dispatch order), regardless of
                    // the order they were declared in — so these two top-level calls
                    // must appear in that same order here for the re-parsed diagram's
                    // flat `elements` list to line up with this one.
                    generalization(specific = auditLog, general = notifier)
                    realization(implementing = greeterImpl, iface = greeter)
                    association(source = customer, target = order) {
                        name = "places"
                        aggregation = AggregationKind.COMPOSITE
                        stereotypes += "FK"
                        source { navigable = false }
                        target {
                            multiplicity("0..*")
                            role = "orders"
                        }
                    }
                    dependency(client = order, supplier = notifier, name = "notifies")
                    comment(text = "General remark, not attached to anything.")
                    comment(text = "Encapsulates the order lifecycle.", order, customer)
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "attribute defaultValue and isStatic round-trip" {
            val original =
                classDiagram("D") {
                    classOf("Config") {
                        attribute(name = "maxRetries", type = "Int", defaultValue = "3", isStatic = true)
                    }
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "adversarial string content (quotes, backslashes, template-injection payloads, embedded newlines) round-trips byte-identically" {
            // Regression test for a security-review finding: verifies quote()'s escaping survives
            // the REAL compiler, not just a substring assertion on the printed text. `${'$'}{...}`
            // is Kotlin's own string-template escape for a literal `${...}` sequence - the actual
            // adversarial payload being tested is the four literal characters `$`, `{`, `}` inside
            // a name, which could otherwise inject a Kotlin string-template expression into the
            // printed script if quote() didn't escape `$`.
            val trickyName = "Foo\"Bar\\Baz\${'$'}{1+1}\nLine2\r\tTabbed"
            val original =
                classDiagram("D") {
                    classOf(trickyName) {
                        attribute(name = "field", type = "String", defaultValue = trickyName)
                    }
                }

            val printed = UmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }
    })
