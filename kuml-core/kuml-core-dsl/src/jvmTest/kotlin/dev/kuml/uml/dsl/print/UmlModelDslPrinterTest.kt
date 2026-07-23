package dev.kuml.uml.dsl.print

import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.ParameterDirection
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class UmlModelDslPrinterTest :
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

        test("simple class is printed with classOf(), name and id") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo")
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "classDiagram(name = \"D\")"
            out shouldContain "classOf(name = \"Foo\", id = \"kt:Foo\")"
        }

        test("print(KumlModel) and print(KumlDiagram) overloads agree") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo")
            val diagram = makeDiagram(listOf(cls))
            val model = makeModel(listOf(cls))
            UmlModelDslPrinter.print(diagram) shouldContain "classOf(name = \"Foo\", id = \"kt:Foo\")"
            UmlModelDslPrinter.print(model) shouldContain "classOf(name = \"Foo\", id = \"kt:Foo\")"
        }

        test("data class stereotype is emitted as stereotypes +=") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo", stereotypes = listOf("data"))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "stereotypes += \"data\""
        }

        test("abstract class emits isAbstract = true inside the block") {
            val cls = UmlClass(id = "kt:Shape", name = "Shape", isAbstract = true)
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "isAbstract = true"
        }

        test("attribute with collection multiplicity is rendered via parseMultiplicity") {
            val attr =
                UmlProperty(
                    id = "kt:Foo#xs",
                    name = "xs",
                    type = UmlTypeRef(name = "List<Int>"),
                    multiplicity = Multiplicity(lower = 0, upper = null),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", attributes = listOf(attr))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "attribute(name = \"xs\", type = \"List<Int>\", multiplicity = parseMultiplicity(\"0..*\"))"
        }

        test("attribute with stereotypes uses the block form") {
            val attr =
                UmlProperty(
                    id = "kt:Foo#id",
                    name = "id",
                    type = UmlTypeRef(name = "UUID"),
                    isReadOnly = true,
                    stereotypes = listOf("PK"),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", attributes = listOf(attr))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "attribute(name = \"id\", type = \"UUID\") {"
            out shouldContain "isReadOnly = true"
            out shouldContain "stereotypes += \"PK\""
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
            val out = UmlModelDslPrinter.print(makeModel(listOf(e)))
            out shouldContain "enumOf(name = \"Color\", id = \"kt:Color\")"
            out shouldContain "literal(\"RED\", id = \"kt:Color:RED\")"
            out shouldContain "literal(\"GREEN\", id = \"kt:Color:GREEN\")"
        }

        test("generalization is printed with classifier ids, not names") {
            val parent = UmlClass(id = "kt:Animal", name = "Animal")
            val child = UmlClass(id = "kt:Dog", name = "Dog")
            val gen =
                UmlGeneralization(
                    id = "gen:1",
                    specificId = "kt:Dog",
                    generalId = "kt:Animal",
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(parent, child, gen)))
            out shouldContain "generalization(specificId = \"kt:Dog\", generalId = \"kt:Animal\")"
        }

        test("association with target multiplicity renders a source{}/target{} block") {
            val a = UmlClass(id = "kt:Owner", name = "Owner")
            val b = UmlClass(id = "kt:Pet", name = "Pet")
            val assoc =
                UmlAssociation(
                    id = "assoc:1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "kt:Owner", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "kt:Pet", multiplicity = Multiplicity(0, null)),
                        ),
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(a, b, assoc)))
            out shouldContain "association(sourceId = \"kt:Owner\", targetId = \"kt:Pet\") {"
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
            val out = UmlModelDslPrinter.print(makeModel(listOf(a, b, assoc)))
            out shouldContain "association(sourceId = \"kt:Owner\", targetId = \"kt:Pet\")\n"
        }

        test("operation with returnType and suspend stereotype is printed via block form") {
            val op =
                UmlOperation(
                    id = "kt:Foo#fetch",
                    name = "fetch",
                    returnType = UmlTypeRef(name = "String"),
                    stereotypes = listOf("suspend"),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "operation(name = \"fetch\") {"
            out shouldContain "returnType = typeRef(\"String\")"
            out shouldContain "stereotypes += \"suspend\""
        }

        test("operation with no extras is printed as a one-liner") {
            val op = UmlOperation(id = "kt:Foo#greet", name = "greet")
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "operation(name = \"greet\")\n"
        }

        test("interface is printed with interfaceOf() builder") {
            val iface = UmlInterface(id = "kt:Greeter", name = "Greeter")
            val out = UmlModelDslPrinter.print(makeModel(listOf(iface)))
            out shouldContain "interfaceOf(name = \"Greeter\", id = \"kt:Greeter\")"
        }

        test("realization is printed with implementingId/interfaceId") {
            val impl = UmlClass(id = "kt:OrderSvc", name = "OrderSvc")
            val iface = UmlInterface(id = "kt:IOrderSvc", name = "IOrderSvc")
            val real =
                dev.kuml.uml.UmlInterfaceRealization(
                    id = "real:1",
                    implementingId = "kt:OrderSvc",
                    interfaceId = "kt:IOrderSvc",
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(impl, iface, real)))
            out shouldContain "realization(implementingId = \"kt:OrderSvc\", interfaceId = \"kt:IOrderSvc\")"
        }

        test("class with grid layout metadata emits a layout block") {
            val cls =
                UmlClass(
                    id = "kt:Alpha",
                    name = "Alpha",
                    metadata =
                        mapOf(
                            LayoutMetadataKeys.GRID_COL to KumlMetaValue.Integer(1),
                            LayoutMetadataKeys.GRID_ROW to KumlMetaValue.Integer(0),
                        ),
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "import dev.kuml.core.dsl.layout.layout"
            out shouldContain "layout {"
            out shouldContain "col = 1"
            out shouldContain "row = 0"
        }

        test("class without layout metadata emits no layout block and no layout import") {
            val cls = UmlClass(id = "kt:Alpha", name = "Alpha")
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "classOf(name = \"Alpha\""
            (out.contains("layout {")) shouldBe false
            (out.contains("import dev.kuml.core.dsl.layout.layout")) shouldBe false
        }

        test("pinned flag is emitted alongside grid coordinates") {
            val cls =
                UmlClass(
                    id = "kt:Alpha",
                    name = "Alpha",
                    metadata =
                        mapOf(
                            LayoutMetadataKeys.GRID_COL to KumlMetaValue.Integer(2),
                            LayoutMetadataKeys.GRID_ROW to KumlMetaValue.Integer(3),
                            LayoutMetadataKeys.PINNED to KumlMetaValue.Flag(true),
                        ),
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "pinned = true"
        }

        test("model root that is not a KumlDiagram is handled gracefully") {
            val model =
                KumlModel(
                    root =
                        dev.kuml.uml.UmlClass(id = "kt:Foo", name = "Foo"),
                    language = ModelingLanguage.UML,
                    level = ModelLevel.PIM,
                    name = "D",
                )
            val out = UmlModelDslPrinter.print(model)
            out shouldContain "cannot serialize"
        }

        // ── V3.2.23 wave: dependency, constraint, comment, association extras,
        //    operation parameters, non-default visibility ──────────────────────

        test("dependency without name is printed") {
            val dep = UmlDependency(id = "dep:1", clientId = "kt:Order", supplierId = "kt:Notifier")
            val out = UmlModelDslPrinter.print(makeModel(listOf(dep)))
            out shouldContain "dependency(clientId = \"kt:Order\", supplierId = \"kt:Notifier\")"
        }

        test("dependency with name is printed") {
            val dep =
                UmlDependency(
                    id = "dep:1",
                    clientId = "kt:Order",
                    supplierId = "kt:Notifier",
                    name = "notifies",
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(dep)))
            out shouldContain "dependency(clientId = \"kt:Order\", supplierId = \"kt:Notifier\", name = \"notifies\")"
        }

        test("plain invariant constraint on a class omits kind and contextOperation") {
            val constraint = UmlConstraint(id = "c:1", name = "hasId", body = "self.id->notEmpty()")
            val cls = UmlClass(id = "kt:Foo", name = "Foo", constraints = listOf(constraint))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "constraint(name = \"hasId\", body = \"self.id->notEmpty()\")"
            out shouldNotContain "kind ="
            out shouldNotContain "contextOperation ="
        }

        test("precondition constraint emits kind and contextOperation") {
            val constraint =
                UmlConstraint(
                    id = "c:1",
                    name = "PreOk",
                    body = "self.x > 0",
                    kind = UmlConstraintKind.Precondition,
                    contextOperation = "place",
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", constraints = listOf(constraint))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain
                "constraint(name = \"PreOk\", body = \"self.x > 0\", " +
                "kind = UmlConstraintKind.Precondition, contextOperation = \"place\")"
        }

        test("constraint on an interface is also printed") {
            val constraint = UmlConstraint(id = "c:1", name = "hasOps", body = "self.operations->notEmpty()")
            val iface = UmlInterface(id = "kt:Greeter", name = "Greeter", constraints = listOf(constraint))
            val out = UmlModelDslPrinter.print(makeModel(listOf(iface)))
            out shouldContain "constraint(name = \"hasOps\", body = \"self.operations->notEmpty()\")"
        }

        test("free-standing comment (zero anchors) omits the anchors argument") {
            val comment = UmlComment(id = "cmt-1", body = "General remark, not attached to anything.")
            val out = UmlModelDslPrinter.print(makeModel(listOf(comment)))
            out shouldContain "comment(text = \"General remark, not attached to anything.\", id = \"cmt-1\")"
        }

        test("comment with multiple anchors is printed with anchors = arrayOf(...)") {
            val comment = UmlComment(id = "cmt-1", body = "Applies to both.")
            val link1 = UmlCommentLink(id = "link:1", commentId = "cmt-1", annotatedElementId = "kt:Order")
            val link2 = UmlCommentLink(id = "link:2", commentId = "cmt-1", annotatedElementId = "kt:OrderItem")
            val out = UmlModelDslPrinter.print(makeModel(listOf(comment, link1, link2)))
            out shouldContain
                "comment(text = \"Applies to both.\", anchors = arrayOf(\"kt:Order\", \"kt:OrderItem\"), id = \"cmt-1\")"
        }

        test("UmlCommentLink is never printed as a standalone relationship") {
            val comment = UmlComment(id = "cmt-1", body = "Anchored.")
            val link = UmlCommentLink(id = "link:1", commentId = "cmt-1", annotatedElementId = "kt:Order")
            val out = UmlModelDslPrinter.print(makeModel(listOf(comment, link)))
            out shouldNotContain "UmlCommentLink"
            out shouldNotContain "link:1"
        }

        test("association with name, aggregation, role and navigable=false is printed") {
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
                            UmlAssociationEnd(typeId = "kt:Order", role = "orders", multiplicity = Multiplicity(0, null)),
                        ),
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(a, b, assoc)))
            out shouldContain "name = \"places\""
            out shouldContain "aggregation = AggregationKind.COMPOSITE"
            out shouldContain "source { navigable = false }"
            out shouldContain "target { multiplicity(\"0..*\"); role = \"orders\" }"
        }

        test("association stereotypes are emitted as stereotypes += and force block form") {
            val a = UmlClass(id = "kt:Customer", name = "Customer")
            val b = UmlClass(id = "kt:Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "assoc:1",
                    stereotypes = listOf("FK"),
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "kt:Customer"),
                            UmlAssociationEnd(typeId = "kt:Order"),
                        ),
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(a, b, assoc)))
            out shouldContain "association(sourceId = \"kt:Customer\", targetId = \"kt:Order\") {"
            out shouldContain "stereotypes += \"FK\""
        }

        test("operation with parameters (in order) and OUT direction + defaultValue is printed") {
            val op =
                UmlOperation(
                    id = "kt:Foo#place",
                    name = "place",
                    parameters =
                        listOf(
                            UmlParameter(id = "p1", name = "items", type = UmlTypeRef(name = "List<OrderItem>")),
                            UmlParameter(
                                id = "p2",
                                name = "flags",
                                type = UmlTypeRef(name = "Int"),
                                direction = ParameterDirection.OUT,
                                defaultValue = "0",
                            ),
                        ),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "parameter(name = \"items\", type = \"List<OrderItem>\")"
            out shouldContain "parameter(name = \"flags\", type = \"Int\", direction = ParameterDirection.OUT, defaultValue = \"0\")"
            // ordering: items must appear before flags
            (out.indexOf("\"items\"") < out.indexOf("\"flags\"")) shouldBe true
        }

        test("static operation emits isStatic = true") {
            val op = UmlOperation(id = "kt:Foo#make", name = "make", isStatic = true)
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "isStatic = true"
        }

        test("non-default operation visibility (PRIVATE) is emitted") {
            val op = UmlOperation(id = "kt:Foo#secret", name = "secret", visibility = Visibility.PRIVATE)
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "visibility = Visibility.PRIVATE"
        }

        test("attribute defaultValue and isStatic are emitted (flat form)") {
            val attr =
                UmlProperty(
                    id = "kt:Foo#x",
                    name = "x",
                    type = UmlTypeRef(name = "Int"),
                    defaultValue = "0",
                    isStatic = true,
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", attributes = listOf(attr))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "defaultValue = \"0\""
            out shouldContain "isStatic = true"
        }

        test("attribute defaultValue and isStatic are emitted (block form, with stereotypes)") {
            val attr =
                UmlProperty(
                    id = "kt:Foo#x",
                    name = "x",
                    type = UmlTypeRef(name = "Int"),
                    defaultValue = "0",
                    isStatic = true,
                    stereotypes = listOf("PK"),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", attributes = listOf(attr))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "defaultValue = \"0\""
            out shouldContain "isStatic = true"
        }

        test("attribute with non-default (PUBLIC) visibility is emitted") {
            val attr =
                UmlProperty(
                    id = "kt:Foo#x",
                    name = "x",
                    type = UmlTypeRef(name = "Int"),
                    visibility = Visibility.PUBLIC,
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", attributes = listOf(attr))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "visibility = Visibility.PUBLIC"
        }

        test("attribute with default (PRIVATE) visibility emits no visibility argument") {
            val attr = UmlProperty(id = "kt:Foo#x", name = "x", type = UmlTypeRef(name = "Int"))
            val cls = UmlClass(id = "kt:Foo", name = "Foo", attributes = listOf(attr))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldNotContain "visibility ="
        }

        test("class with non-default (PROTECTED) visibility is emitted") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo", visibility = Visibility.PROTECTED)
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "visibility = Visibility.PROTECTED"
        }

        test("interface with non-default (PROTECTED) visibility is emitted") {
            val iface = UmlInterface(id = "kt:Greeter", name = "Greeter", visibility = Visibility.PROTECTED)
            val out = UmlModelDslPrinter.print(makeModel(listOf(iface)))
            out shouldContain "visibility = Visibility.PROTECTED"
        }

        test("enumeration with non-default (PROTECTED) visibility is emitted") {
            val e = UmlEnumeration(id = "kt:Color", name = "Color", visibility = Visibility.PROTECTED)
            val out = UmlModelDslPrinter.print(makeModel(listOf(e)))
            out shouldContain "visibility = Visibility.PROTECTED"
        }

        test("attribute referencing a classifier by id round-trips referencedId via typeRef(...)") {
            val attr =
                UmlProperty(
                    id = "kt:Order#status",
                    name = "status",
                    type = UmlTypeRef(name = "OrderStatus", referencedId = "kt:OrderStatus"),
                )
            val cls = UmlClass(id = "kt:Order", name = "Order", attributes = listOf(attr))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "type = typeRef(\"OrderStatus\", referencedId = \"kt:OrderStatus\")"
        }

        test("UmlPackage is not printed as a builder call — a TODO marker is emitted instead") {
            val pkg = UmlPackage(id = "kt:domain", name = "domain")
            val out = UmlModelDslPrinter.print(makeModel(listOf(pkg)))
            out shouldNotContain "packageOf("
            out shouldContain "TODO"
            out shouldContain "domain"
        }

        test("quote() escapes backslash, double-quote, dollar and newline for constraint bodies") {
            val constraint =
                UmlConstraint(
                    id = "c:1",
                    name = "weird",
                    body = "line1\\n\"quoted\" \$var\nline2",
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", constraints = listOf(constraint))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "\\\\n" // the literal backslash-n in the original body, escaped
            out shouldContain "\\\"quoted\\\""
            out shouldContain "\\\$var"
            // the embedded real newline must NOT split the generated statement across
            // two lines of the printed script — the whole constraint(...) call,
            // including the text after the newline, stays on one output line.
            out.lines().any {
                it.contains("constraint(name = \"weird\"") && it.contains("line2")
            } shouldBe true
        }
    })
