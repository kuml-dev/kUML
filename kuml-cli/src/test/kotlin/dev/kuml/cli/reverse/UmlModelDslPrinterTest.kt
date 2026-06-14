package dev.kuml.cli.reverse

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
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
import io.kotest.matchers.string.shouldContain

class UmlModelDslPrinterTest :
    FunSpec({

        fun makeModel(elements: List<dev.kuml.core.model.KumlElement>): KumlModel {
            val diagram =
                KumlDiagram(
                    id = "D",
                    name = "D",
                    type = DiagramType.CLASS,
                    elements = elements,
                )
            return KumlModel(
                root = diagram,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "D",
            )
        }

        test("simple class is printed with name and no extras") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo")
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "classDiagram(name = \"D\")"
            out shouldContain "class(name = \"Foo\")"
            out shouldContain "}"
        }

        test("data class stereotype is emitted as stereotypes = listOf(...)") {
            val cls = UmlClass(id = "kt:Foo", name = "Foo", stereotypes = listOf("data"))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "stereotypes = listOf(\"data\")"
        }

        test("attribute with collection multiplicity is rendered with multiplicity arg") {
            val attr =
                UmlProperty(
                    id = "kt:Foo#xs",
                    name = "xs",
                    type = UmlTypeRef(name = "List<Int>"),
                    multiplicity = Multiplicity(lower = 0, upper = null),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", attributes = listOf(attr))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "attribute(name = \"xs\", type = \"List<Int>\", multiplicity = \"0..*\")"
        }

        test("enumeration with literals is printed") {
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
            out shouldContain "enum(name = \"Color\")"
            out shouldContain "literal(\"RED\")"
            out shouldContain "literal(\"GREEN\")"
        }

        test("generalization is printed with classifier names") {
            val parent = UmlClass(id = "kt:Animal", name = "Animal")
            val child = UmlClass(id = "kt:Dog", name = "Dog")
            val gen =
                UmlGeneralization(
                    id = "gen:1",
                    specificId = "kt:Dog",
                    generalId = "kt:Animal",
                )
            val out = UmlModelDslPrinter.print(makeModel(listOf(parent, child, gen)))
            out shouldContain "generalization(specific = \"Dog\", general = \"Animal\")"
        }

        test("association with targetMul renders sourceMul/targetMul where non-default") {
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
            out shouldContain "association(source = \"Owner\", target = \"Pet\", targetMul = \"0..*\")"
        }

        test("operation with returnType and suspend stereotype is printed") {
            val op =
                UmlOperation(
                    id = "kt:Foo#fetch",
                    name = "fetch",
                    returnType = UmlTypeRef(name = "String"),
                    stereotypes = listOf("suspend"),
                )
            val cls = UmlClass(id = "kt:Foo", name = "Foo", operations = listOf(op))
            val out = UmlModelDslPrinter.print(makeModel(listOf(cls)))
            out shouldContain "operation(name = \"fetch\", returnType = \"String\", stereotypes = listOf(\"suspend\"))"
        }

        test("interface is printed with interface() builder") {
            val iface = UmlInterface(id = "kt:Greeter", name = "Greeter")
            val out = UmlModelDslPrinter.print(makeModel(listOf(iface)))
            out shouldContain "interface(name = \"Greeter\")"
        }
    })
