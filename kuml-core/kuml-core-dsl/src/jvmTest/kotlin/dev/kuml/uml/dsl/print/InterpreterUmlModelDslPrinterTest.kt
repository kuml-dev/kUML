package dev.kuml.uml.dsl.print

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlConstraintKind
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.measureTime

/**
 * Shape/text tests for [InterpreterUmlModelDslPrinter] — mirrors
 * [UmlModelDslPrinterTest]'s style (raw [KumlDiagram] / data-class fixtures,
 * `shouldContain`/`shouldNotContain`, no evaluator dependency). Real
 * round-trip fidelity through [dev.kuml.core.script.interpreter.InterpreterScriptEvaluator]
 * lives in `kuml-core-script`'s `InterpreterPrinterRoundTripTest`.
 */
class InterpreterUmlModelDslPrinterTest :
    FunSpec({

        fun makeDiagram(elements: List<dev.kuml.core.model.KumlElement>): KumlDiagram =
            KumlDiagram(
                id = "D",
                name = "D",
                type = DiagramType.CLASS,
                elements = elements,
            )

        fun makeModel(elements: List<dev.kuml.core.model.KumlElement>): KumlModel =
            KumlModel(
                root = makeDiagram(elements),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "D",
            )

        test("simple class is printed as a val classOf(...) binding") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "classDiagram(name = \"D\")"
            out shouldContain "val foo = classOf(name = \"Foo\", id = \"kt:Foo\")"
        }

        test("print(KumlModel) and print(KumlDiagram) overloads agree") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo")
            val diagram = makeDiagram(listOf(cls))
            val model = makeModel(listOf(cls))
            InterpreterUmlModelDslPrinter.print(diagram) shouldContain "val foo = classOf(name = \"Foo\", id = \"kt:Foo\")"
            InterpreterUmlModelDslPrinter.print(model) shouldContain "val foo = classOf(name = \"Foo\", id = \"kt:Foo\")"
        }

        test("data class stereotype is never printed (no += operator in the interpreter grammar)") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo", stereotypes = listOf("data"))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldNotContain "stereotypes"
            out shouldNotContain "+="
        }

        test("abstract class emits isAbstract = true inside the block") {
            val cls = UmlClass(id = "kt:Shape", name = "Shape", isAbstract = true)
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "isAbstract = true"
        }

        test("class with non-default (PROTECTED) visibility is emitted") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo", visibility = Visibility.PROTECTED)
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "visibility = Visibility.PROTECTED"
        }

        test("interface with non-default visibility is NOT emitted (unrepresentable through the interpreter)") {
            val iface = UmlInterface(id = "kt:Greeter", name = "Greeter", visibility = Visibility.PROTECTED)
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(iface)))
            out shouldContain "val greeter = interfaceOf(name = \"Greeter\", id = \"kt:Greeter\")"
            out shouldNotContain "visibility ="
        }

        test("enumeration with non-default visibility is NOT emitted (unrepresentable through the interpreter)") {
            val e = UmlEnumeration(id = "kt:Color", name = "Color", visibility = Visibility.PROTECTED)
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(e)))
            out shouldContain "val color = enumOf(name = \"Color\", id = \"kt:Color\")"
            out shouldNotContain "visibility ="
        }

        test("attribute uses the flat form always, even with stereotypes present on the data class") {
            val attr =
                UmlProperty(
                    id = "kt:Foo#id",
                    name = "id",
                    type = UmlTypeRef(name = "UUID"),
                    isReadOnly = true,
                    stereotypes = listOf("PK"),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", attributes = listOf(attr))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "attribute(name = \"id\", type = \"UUID\", isReadOnly = true)"
            out shouldNotContain "attribute(name = \"id\", type = \"UUID\") {" // no block form — always a flat one-liner
            out shouldNotContain "stereotypes"
        }

        test("enumeration with literals and ids is printed") {
            val e =
                UmlEnumeration(
                    id = "kt:Color",
                    name = "Color",
                    literals =
                        listOf(
                            UmlEnumerationLiteral(id = "kt:Color:RED", name = "RED"),
                            UmlEnumerationLiteral(id = "kt:Color:GREEN", name = "GREEN"),
                        ),
                )
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(e)))
            out shouldContain "val color = enumOf(name = \"Color\", id = \"kt:Color\")"
            out shouldContain "literal(\"RED\", id = \"kt:Color:RED\")"
            out shouldContain "literal(\"GREEN\", id = \"kt:Color:GREEN\")"
        }

        test("generalization is printed with val handles, not string ids") {
            val parent = UmlClass(id = "kt:Animal", name = "Animal")
            val child = UmlClass(id = "kt:Dog", name = "Dog")
            val gen = UmlGeneralization(id = "gen:1", specificId = "kt:Dog", generalId = "kt:Animal")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(parent, child, gen)))
            out shouldContain "generalization(specific = dog, general = animal)"
            out shouldNotContain "specificId"
            out shouldNotContain "generalId"
        }

        test("realization is printed with val handles") {
            val impl = UmlClass(id = "kt:OrderSvc", name = "OrderSvc")
            val iface = UmlInterface(id = "kt:IOrderSvc", name = "IOrderSvc")
            val real =
                dev.kuml.uml.UmlInterfaceRealization(
                    id = "real:1",
                    implementingId = "kt:OrderSvc",
                    interfaceId = "kt:IOrderSvc",
                )
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(impl, iface, real)))
            out shouldContain "realization(implementing = orderSvc, iface = iOrderSvc)"
        }

        test("dependency without name is printed with val handles") {
            val a = UmlClass(id = "kt:Order", name = "Order")
            val b = UmlClass(id = "kt:Notifier", name = "Notifier")
            val dep = UmlDependency(id = "dep:1", clientId = "kt:Order", supplierId = "kt:Notifier")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(a, b, dep)))
            out shouldContain "dependency(client = order, supplier = notifier)"
        }

        test("dependency with name is printed") {
            val a = UmlClass(id = "kt:Order", name = "Order")
            val b = UmlClass(id = "kt:Notifier", name = "Notifier")
            val dep = UmlDependency(id = "dep:1", clientId = "kt:Order", supplierId = "kt:Notifier", name = "notifies")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(a, b, dep)))
            out shouldContain "dependency(client = order, supplier = notifier, name = \"notifies\")"
        }

        test("association with target multiplicity renders a source{}/target{} block using val handles") {
            val a = UmlClass(id = "kt:Owner", name = "Owner")
            val b = UmlClass(id = "kt:Pet", name = "Pet")
            val assoc =
                UmlAssociation(
                    id = "assoc:1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "kt:Owner", multiplicity = Multiplicity(lower = 1, upper = 1)),
                            UmlAssociationEnd(typeId = "kt:Pet", multiplicity = Multiplicity(lower = 0, upper = null)),
                        ),
                )
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(a, b, assoc)))
            out shouldContain "association(source = owner, target = pet) {"
            out shouldContain "target { multiplicity(\"0..*\") }"
        }

        test("association with only default multiplicities renders the flat single-line form") {
            val a = UmlClass(id = "kt:Owner", name = "Owner")
            val b = UmlClass(id = "kt:Pet", name = "Pet")
            val assoc =
                UmlAssociation(
                    id = "assoc:1",
                    ends = listOf(UmlAssociationEnd(typeId = "kt:Owner"), UmlAssociationEnd(typeId = "kt:Pet")),
                )
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(a, b, assoc)))
            out shouldContain "association(source = owner, target = pet)\n"
        }

        test("association stereotypes are never printed (no += operator in the interpreter grammar)") {
            val a = UmlClass(id = "kt:Customer", name = "Customer")
            val b = UmlClass(id = "kt:Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "assoc:1",
                    stereotypes = listOf("FK"),
                    ends = listOf(UmlAssociationEnd(typeId = "kt:Customer"), UmlAssociationEnd(typeId = "kt:Order")),
                )
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(a, b, assoc)))
            // The flat form is used since name/aggregation/ends are all default —
            // stereotypes alone must NOT force the block form here, unlike the compiler dialect.
            out shouldContain "association(source = customer, target = order)\n"
            out shouldNotContain "stereotypes"
        }

        test("self-association declares the val once and references it as both source and target") {
            val node = UmlClass(id = "kt:Node", name = "Node")
            val assoc =
                UmlAssociation(
                    id = "assoc:1",
                    ends = listOf(UmlAssociationEnd(typeId = "kt:Node"), UmlAssociationEnd(typeId = "kt:Node", role = "parent")),
                )
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(node, assoc)))
            out shouldContain "val node = classOf(name = \"Node\", id = \"kt:Node\")"
            out shouldContain "association(source = node, target = node) {"
            out shouldContain "target { role = \"parent\" }"
        }

        test("operation with returnType is printed via returns(typeName = ...), not a returnType property") {
            val op = UmlOperation(id = "kt:Foo#fetch", name = "fetch", returnType = UmlTypeRef(name = "String"))
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "operation(name = \"fetch\") {"
            out shouldContain "returns(typeName = \"String\")"
            out shouldNotContain "returnType ="
        }

        test("operation with no extras is printed as a one-liner") {
            val op = UmlOperation(id = "kt:Foo#greet", name = "greet")
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "operation(name = \"greet\")\n"
        }

        test("interface is printed with interfaceOf() bound to a val") {
            val iface = UmlInterface(id = "kt:Greeter", name = "Greeter")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(iface)))
            out shouldContain "val greeter = interfaceOf(name = \"Greeter\", id = \"kt:Greeter\")"
        }

        test("plain invariant constraint on a class omits kind and contextOperation") {
            val constraint = UmlConstraint(id = "c:1", name = "hasId", body = "self.id->notEmpty()")
            val cls = UmlClass(id = "kt:Foo", name = "Foo", constraints = listOf(constraint))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "constraint(name = \"hasId\", body = \"self.id->notEmpty()\")"
            out shouldNotContain "kind ="
            out shouldNotContain "contextOperation ="
        }

        test("precondition constraint still emits kind and contextOperation (harmlessly ignored on read-back)") {
            val constraint =
                UmlConstraint(
                    id = "c:1",
                    name = "PreOk",
                    body = "self.x > 0",
                    kind = UmlConstraintKind.Precondition,
                    contextOperation = "place",
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", constraints = listOf(constraint))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain
                "constraint(name = \"PreOk\", body = \"self.x > 0\", " +
                "kind = UmlConstraintKind.Precondition, contextOperation = \"place\")"
        }

        test("free-standing comment (zero anchors) omits firstAnchor and id") {
            val comment = UmlComment(id = "cmt-1", body = "General remark, not attached to anything.")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(comment)))
            out shouldContain "comment(text = \"General remark, not attached to anything.\")\n"
            out shouldNotContain "firstAnchor"
            out shouldNotContain "id = \"cmt-1\""
        }

        test("comment with a single anchor is printed with firstAnchor = <val>") {
            val order = UmlClass(id = "kt:Order", name = "Order")
            val comment = UmlComment(id = "cmt-1", body = "Applies to the order.")
            val link = UmlCommentLink(id = "link:1", commentId = "cmt-1", annotatedElementId = "kt:Order")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(order, comment, link)))
            out shouldContain "comment(text = \"Applies to the order.\", firstAnchor = order)"
        }

        test("comment with multiple anchors keeps only firstAnchor and emits a TODO for the rest") {
            val order = UmlClass(id = "kt:Order", name = "Order")
            val item = UmlClass(id = "kt:OrderItem", name = "OrderItem")
            val comment = UmlComment(id = "cmt-1", body = "Applies to both.")
            val link1 = UmlCommentLink(id = "link:1", commentId = "cmt-1", annotatedElementId = "kt:Order")
            val link2 = UmlCommentLink(id = "link:2", commentId = "cmt-1", annotatedElementId = "kt:OrderItem")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(order, item, comment, link1, link2)))
            out shouldContain "comment(text = \"Applies to both.\", firstAnchor = order)"
            out shouldNotContain "orderItem)"
            out shouldContain "TODO"
            out shouldContain "1 more anchor"
        }

        test("UmlCommentLink is never printed as a standalone relationship") {
            val comment = UmlComment(id = "cmt-1", body = "Anchored.")
            val cls = UmlClass(id = "kt:Order", name = "Order")
            val link = UmlCommentLink(id = "link:1", commentId = "cmt-1", annotatedElementId = "kt:Order")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls, comment, link)))
            out shouldNotContain "UmlCommentLink"
            out shouldNotContain "link:1"
        }

        test("attribute referencing an enum classifier by id uses the bound val handle") {
            val statusEnum = UmlEnumeration(id = "kt:OrderStatus", name = "OrderStatus")
            val attr =
                UmlProperty(
                    id = "kt:Order#status",
                    name = "status",
                    type = UmlTypeRef(name = "OrderStatus", referencedId = "kt:OrderStatus"),
                )
            val cls = UmlClass(id = "kt:Order", name = "Order", attributes = listOf(attr))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(statusEnum, cls)))
            out shouldContain "attribute(name = \"status\", type = orderStatus)"
        }

        test("attribute referencing a classifier NOT present in the diagram falls back to a plain string type") {
            val attr =
                UmlProperty(
                    id = "kt:Order#status",
                    name = "status",
                    type = UmlTypeRef(name = "OrderStatus", referencedId = "kt:NotInThisDiagram"),
                )
            val cls = UmlClass(id = "kt:Order", name = "Order", attributes = listOf(attr))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "attribute(name = \"status\", type = \"OrderStatus\")"
        }

        test("parameter type referencedId is always dropped in favor of a plain string type") {
            val op =
                UmlOperation(
                    id = "kt:Foo#find",
                    name = "find",
                    parameters =
                        listOf(
                            UmlParameter(
                                id = "p1",
                                name = "order",
                                type = UmlTypeRef(name = "Order", referencedId = "kt:Order"),
                            ),
                        ),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "parameter(name = \"order\", type = \"Order\")"
            out shouldNotContain "referencedId"
        }

        test("two classifiers whose names sanitize to the same identifier get a disambiguating suffix") {
            val a = UmlClass(id = "kt:Foo", name = "Foo")
            val b = UmlClass(id = "kt:foo2", name = "foo")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(a, b)))
            out shouldContain "val foo = classOf(name = \"Foo\", id = \"kt:Foo\")"
            out shouldContain "val foo_2 = classOf(name = \"foo\", id = \"kt:foo2\")"
        }

        test("a classifier named after a Kotlin hard keyword gets a disambiguating suffix on its identifier") {
            val cls = UmlClass(id = "kt:val", name = "val")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "val val_2 = classOf(name = \"val\", id = \"kt:val\")"
            out shouldNotContain "val val ="
        }

        test("class with grid layout metadata emits no layout block and no layout import (unrepresentable)") {
            val cls =
                UmlClass(
                    id = "kt:Alpha",
                    name = "Alpha",
                    metadata =
                        mapOf(
                            dev.kuml.core.dsl.layout.LayoutMetadataKeys.GRID_COL to
                                dev.kuml.core.model.KumlMetaValue
                                    .Integer(1),
                            dev.kuml.core.dsl.layout.LayoutMetadataKeys.GRID_ROW to
                                dev.kuml.core.model.KumlMetaValue
                                    .Integer(0),
                        ),
                )
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldNotContain "layout {"
            out shouldNotContain "import dev.kuml.core.dsl.layout.layout"
        }

        test("UmlPackage is not printed as a builder call — a TODO marker is emitted instead") {
            val pkg = UmlPackage(id = "kt:domain", name = "domain")
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(pkg)))
            out shouldNotContain "packageOf("
            out shouldContain "TODO"
            out shouldContain "domain"
        }

        test("model root that is not a KumlDiagram is handled gracefully") {
            val model =
                KumlModel(
                    root = dev.kuml.uml.UmlClass(id = "kt:Foo", name = "Foo"),
                    language = ModelingLanguage.UML,
                    level = ModelLevel.PIM,
                    name = "D",
                )
            val out = InterpreterUmlModelDslPrinter.print(model)
            out shouldContain "cannot serialize"
        }

        test("quote() escapes backslash, double-quote, dollar and newline for constraint bodies") {
            val constraint =
                UmlConstraint(
                    id = "c:1",
                    name = "weird",
                    body = "line1\\n\"quoted\" \$var\nline2",
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", constraints = listOf(constraint))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "\\\\n"
            out shouldContain "\\\"quoted\\\""
            out shouldContain "\\\$var"
        }

        test("classifier and attribute NAMEs with adversarial string content are safely escaped, not just bodies/defaults") {
            // Regression guard: earlier tests only exercised quote() escaping via a
            // constraint body or an attribute defaultValue. printClass/printAttribute
            // route name= through the same quote() function, but that was never
            // pinned by a dedicated test — a future "skip quoting for simple-looking
            // identifiers" fast path on name= could silently reintroduce a
            // string-literal-breakout bug without any test catching it.
            val adversarialName = "Foo\") { classOf(\"Evil\\n\$var"
            val attr =
                UmlProperty(
                    id = "kt:Foo#attr",
                    name = adversarialName,
                    type = UmlTypeRef(name = "String"),
                )
            val cls = UmlClass(id = "kt:Foo", name = adversarialName, attributes = listOf(attr))
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(cls)))
            // The quote/backslash/dollar characters in the NAME must be escaped exactly
            // like quote() escapes them anywhere else (bodies, defaultValues, ...).
            out shouldContain "\\\")" // the embedded `")` is escaped, not a raw break-out
            out shouldContain "classOf(\\\"Evil"
            out shouldContain "\\\\n" // literal backslash+n stays escaped
            out shouldContain "\\\$var" // dollar sign is escaped
            // The raw, unescaped payload must never appear verbatim in the output —
            // that would mean it broke out of its string-literal context.
            out shouldNotContain "\") { classOf(\"Evil"
        }

        test("AggregationKind and name on an association are printed") {
            val a = UmlClass(id = "kt:Customer", name = "Customer")
            val b = UmlClass(id = "kt:Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "assoc:1",
                    name = "places",
                    aggregation = AggregationKind.COMPOSITE,
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "kt:Customer", navigable = false),
                            UmlAssociationEnd(typeId = "kt:Order", role = "orders", multiplicity = Multiplicity(lower = 0, upper = null)),
                        ),
                )
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(a, b, assoc)))
            out shouldContain "name = \"places\""
            out shouldContain "aggregation = AggregationKind.COMPOSITE"
            out shouldContain "source { navigable = false }"
            out shouldContain "target { multiplicity(\"0..*\"); role = \"orders\" }"
        }

        test(
            "a 200,000-level-deep UmlPackage chain is printed (TODO marker) without a StackOverflowError " +
                "(regression guard: countNestedMembers must stay iterative, not recursive)",
        ) {
            // Built bottom-up with a plain loop (never recursively) so that constructing
            // the fixture itself cannot overflow the stack — only `print()`'s internal
            // walk of it is under test here.
            val depth = 200_000
            var innermost: dev.kuml.core.model.KumlElement = UmlClass(id = "kt:Leaf", name = "Leaf")
            repeat(depth) { i ->
                innermost = UmlPackage(id = "pkg:$i", name = "pkg$i", members = listOf(innermost as dev.kuml.uml.UmlNamedElement))
            }
            val topPackage = innermost as UmlPackage

            // The call itself must not throw StackOverflowError — a naive recursive
            // countNestedMembers would blow the JVM call stack here.
            val out = InterpreterUmlModelDslPrinter.print(makeModel(listOf(topPackage)))

            out shouldContain "TODO"
            out shouldContain "not serialized"
            // One nested UmlPackage per level (199,999 nested packages below the top one)
            // plus the single leaf UmlClass at the bottom = 200,000 counted members.
            out shouldContain "200000 nested member(s)"
        }

        test(
            "10,000 comments x 10,000 comment-links resolve within a generous time budget " +
                "(regression anchor: printComments must stay O(comments + links), not O(comments * links))",
        ) {
            // Every comment is linked from every link (worst case for the naive
            // per-comment `links.filter { ... }` approach: O(comments * links) ==
            // 10,000 * 10,000 == 10^8 comparisons for this single diagram).
            val commentCount = 10_000
            val cls = UmlClass(id = "kt:Anchor", name = "Anchor")
            val comments = (1..commentCount).map { i -> UmlComment(id = "cmt-$i", body = "note $i") }
            val links =
                comments.map { c ->
                    UmlCommentLink(id = "link-${c.id}", commentId = c.id, annotatedElementId = "kt:Anchor")
                }
            val model = makeModel(listOf(cls) + comments + links)

            val duration = measureTime { InterpreterUmlModelDslPrinter.print(model) }
            println("[InterpreterUmlModelDslPrinter benchmark] printComments/10_000 comments x 10_000 links: $duration")
            // Bound is intentionally loose (order of magnitude headroom over locally
            // observed timings, same style as OclBenchmarkTest) to avoid CI flakiness
            // while still catching an accidental reversion to the O(n^2) filter.
            duration.inWholeMilliseconds shouldBeLessThan 5000L
        }
    })
