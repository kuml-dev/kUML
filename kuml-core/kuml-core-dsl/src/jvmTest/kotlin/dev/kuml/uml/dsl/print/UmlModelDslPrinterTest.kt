package dev.kuml.uml.dsl.print

import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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
    })
