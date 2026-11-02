@file:Suppress("RequireNamedArguments")

package dev.kuml.uml.dsl

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.dsl.deploymentDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlCommunicationPath
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlSerializersModule
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import dev.kuml.uml.displayLine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** PAL wave 2G-a: comment anchors, communication path, attribute display line and attribute type edges. */
class Wave2GaDslTest :
    FunSpec({
        test("comment accepts firstAnchor + secondAnchor, the positional form and three anchors") {
            val d =
                classDiagram(name = "C") {
                    val a = classOf(name = "A")
                    val b = classOf(name = "B")
                    val c = classOf(name = "C")
                    comment(text = "named", firstAnchor = a, secondAnchor = b)
                    comment("positional", a, b)
                    comment("three", a, b, c)
                    comment("single", a, id = "n-single")
                }
            val links = d.elements.filterIsInstance<UmlCommentLink>()
            links.map { it.annotatedElementId } shouldContainExactly listOf("A", "B", "A", "B", "A", "B", "C", "A")
        }

        test("communicationPath is its own type, named only when a name is given") {
            val d =
                deploymentDiagram(name = "D") {
                    val a = node(name = "A")
                    val b = node(name = "B")
                    communicationPath(end1 = a, end2 = b)
                    communicationPath(end1 = b, end2 = a, name = "TLS")
                }
            val paths = d.elements.filterIsInstance<UmlCommunicationPath>()
            paths.map { it.name } shouldContainExactly listOf(null, "TLS")
            d.elements.filterIsInstance<UmlDependency>() shouldHaveSize 0
        }

        test("UmlCommunicationPath survives a JSON round trip") {
            val json =
                Json {
                    serializersModule = UmlSerializersModule
                    classDiscriminator = "@type"
                }
            val original: dev.kuml.core.model.KumlElement = UmlCommunicationPath(id = "p", end1Id = "a", end2Id = "b", name = "x")
            val text = json.encodeToString<dev.kuml.core.model.KumlElement>(original)
            json.decodeFromString<dev.kuml.core.model.KumlElement>(text) shouldBe original
        }

        test("an old deployment dependency named communicationPath is still read as a dependency") {
            val json =
                Json {
                    serializersModule = UmlSerializersModule
                    classDiscriminator = "@type"
                }
            val old: dev.kuml.core.model.KumlElement =
                UmlDependency(id = "d", clientId = "a", supplierId = "b", name = "«communicationPath»")
            val text = json.encodeToString<dev.kuml.core.model.KumlElement>(old)
            (json.decodeFromString<dev.kuml.core.model.KumlElement>(text) as UmlDependency).name shouldBe "«communicationPath»"
        }

        test("attribute display line: visibility defaults and default value verbatim") {
            val d = UmlTypeRef(name = "Int")
            UmlProperty(id = "a", name = "x", type = d).displayLine() shouldBe "- x: Int"
            UmlProperty(id = "a", name = "x", type = d, visibility = Visibility.PUBLIC).displayLine() shouldBe "+ x: Int"
            UmlProperty(id = "a", name = "x", type = d, defaultValue = "-5").displayLine() shouldBe "- x: Int = -5"
            UmlProperty(id = "a", name = "s", type = UmlTypeRef(name = "String"), defaultValue = "\"a b\"").displayLine() shouldBe
                "- s: String = \"a b\""
            dev.kuml.uml
                .UmlOperation(id = "o", name = "f")
                .visibility shouldBe Visibility.PUBLIC
        }

        test("attributeTypeEdges draws one labelled dependency per owner and target, but not twice or against an association") {
            val d =
                classDiagram(name = "T") {
                    attributeTypeEdges = true
                    val customer = classOf(name = "Customer")
                    val address = classOf(name = "Address")
                    val order =
                        classOf(name = "Order") {
                            attribute(name = "billing", type = "Address")
                            attribute(name = "shipping", type = "Address")
                            attribute(name = "owner", type = "Customer")
                            attribute(name = "total", type = "Decimal")
                            attribute(name = "self", type = "Order")
                        }
                    association(sourceId = customer.id, targetId = address.id)
                    order.id
                }
            val deps = d.elements.filterIsInstance<UmlDependency>()
            deps.map { Triple(it.clientId, it.supplierId, it.name) } shouldContainExactly
                listOf(
                    Triple("Order", "Address", "billing, shipping"),
                    Triple("Order", "Customer", "owner"),
                )
            d.elements.filterIsInstance<UmlClass>() shouldHaveSize 3
        }

        test("attributeTypeEdges is off by default") {
            val d =
                classDiagram(name = "T") {
                    classOf(name = "Address")
                    classOf(name = "Order") { attribute(name = "billing", type = "Address") }
                }
            d.elements.filterIsInstance<UmlDependency>() shouldHaveSize 0
        }
    })
