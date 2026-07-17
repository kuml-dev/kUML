package dev.kuml.uml

import dev.kuml.core.model.ClassDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * Round-trip tests for [KumlDiagram] over JSON using [UmlSerializersModule].
 *
 * These exercise the V3.2.10 unblock: `KumlDiagram.elements` is a
 * `List<KumlElement>`, an open polymorphic base. Decoding requires a `Json`
 * configured with a `SerializersModule` that registers the concrete UML
 * subtypes — this is what [UmlSerializersModule] provides.
 */
class KumlDiagramSerializationTest :
    FunSpec(body = {

        val json =
            Json {
                serializersModule = UmlSerializersModule
                classDiscriminator = "@type"
                encodeDefaults = true
            }

        test(name = "KumlDiagram with UML elements round-trips through JSON") {
            val before =
                KumlDiagram(
                    name = "OrderModel",
                    type = DiagramType.CLASS,
                    elements =
                        listOf(
                            UmlClass(id = "Order", name = "Order"),
                            UmlClass(id = "Customer", name = "Customer"),
                            UmlAssociation(
                                id = "assoc-1",
                                ends =
                                    listOf(
                                        UmlAssociationEnd(typeId = "Order", role = "orders"),
                                        UmlAssociationEnd(typeId = "Customer", role = "customer"),
                                    ),
                            ),
                        ),
                )

            val text = json.encodeToString(before)
            val after = json.decodeFromString<KumlDiagram>(text)
            after shouldBe before
        }

        test(name = "KumlDiagram.type field survives untouched alongside the @type discriminator") {
            val before = KumlDiagram(name = "Empty", type = DiagramType.SEQUENCE)
            val text = json.encodeToString(before)
            text shouldContain "\"type\":\"SEQUENCE\""

            val after = json.decodeFromString<KumlDiagram>(text)
            after.type shouldBe DiagramType.SEQUENCE
        }

        test(name = "nested UmlPackage members survive KumlDiagram round-trip") {
            val before =
                KumlDiagram(
                    name = "Nested",
                    type = DiagramType.CLASS,
                    elements =
                        listOf(
                            UmlPackage(
                                id = "domain",
                                name = "domain",
                                members =
                                    listOf(
                                        UmlClass(id = "domain::Order", name = "Order"),
                                        UmlInterface(id = "domain::IRepo", name = "IRepo"),
                                    ),
                            ),
                        ),
                )

            val text = json.encodeToString(before)
            val after = json.decodeFromString<KumlDiagram>(text)
            after shouldBe before

            val pkg = after.elements.single() as UmlPackage
            pkg.members.map { it.name } shouldBe listOf("Order", "IRepo")
        }

        test(name = "metadata and config round-trip alongside polymorphic elements") {
            val before =
                KumlDiagram(
                    name = "WithConfigAndMetadata",
                    type = DiagramType.CLASS,
                    elements = listOf(UmlClass(id = "A", name = "A")),
                    metadata =
                        mapOf(
                            "line" to KumlMetaValue.Integer(value = 7L),
                            "source" to KumlMetaValue.Text(value = "sample.kuml.kts"),
                            "stable" to KumlMetaValue.Flag(value = true),
                        ),
                    config = ClassDiagramConfig(),
                )

            val text = json.encodeToString(before)
            val after = json.decodeFromString<KumlDiagram>(text)
            after shouldBe before
            after.config shouldBe ClassDiagramConfig()
        }

        test(name = "decoding an unregistered KumlElement subtype throws SerializationException") {
            @Serializable
            data class UnregisteredElement(
                override val id: String,
                override val metadata: Map<String, KumlMetaValue> = emptyMap(),
            ) : KumlElement

            val scopedModule =
                SerializersModule {
                    polymorphic(KumlElement::class) {
                        subclass(UnregisteredElement::class, UnregisteredElement.serializer())
                    }
                }
            val encodingJson =
                Json {
                    serializersModule = scopedModule
                    classDiscriminator = "@type"
                }

            val diagram =
                KumlDiagram(
                    name = "Unregistered",
                    elements = listOf(UnregisteredElement(id = "x")),
                )
            val text = encodingJson.encodeToString(diagram)

            // UmlSerializersModule does not know about UnregisteredElement.
            shouldThrow<SerializationException> {
                json.decodeFromString<KumlDiagram>(text)
            }
        }
    })
