package dev.kuml.core.dsl.layout

import dev.kuml.c4.dsl.c4Model
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.core.dsl.componentDiagram
import dev.kuml.core.dsl.stateDiagram
import dev.kuml.core.dsl.useCaseDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlUseCase
import dev.kuml.uml.dsl.actor
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.component
import dev.kuml.uml.dsl.enumOf
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.state
import dev.kuml.uml.dsl.umlModel
import dev.kuml.uml.dsl.useCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LayoutHintsTest : FunSpec(body = {

    // ── 1. Empty layout builder produces empty metadata ────────────────────────

    test("empty layout builder produces empty metadata") {
        val builder = LayoutHintsBuilder()
        builder.toMetadata().shouldBeEmpty()
        builder.isEmpty() shouldBe true
    }

    // ── 2. Grid hints materialize correctly ───────────────────────────────────

    test("grid hints materialize correctly") {
        val builder =
            LayoutHintsBuilder().apply {
                col = 2
                row = 3
                colSpan = 2
                // rowSpan stays at 1 (default) — must NOT appear in metadata
                pinned = true
            }
        val meta = builder.toMetadata()

        meta[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(2L)
        meta[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(3L)
        meta[LayoutMetadataKeys.GRID_COL_SPAN] shouldBe KumlMetaValue.Integer(2L)
        meta[LayoutMetadataKeys.PINNED] shouldBe KumlMetaValue.Flag(true)

        // Default values must NOT produce entries
        meta shouldNotContainKey LayoutMetadataKeys.GRID_ROW_SPAN
    }

    test("col only does not produce row entry") {
        val meta = LayoutHintsBuilder().apply { col = 5 }.toMetadata()
        meta shouldContainKey LayoutMetadataKeys.GRID_COL
        meta shouldNotContainKey LayoutMetadataKeys.GRID_ROW
    }

    test("rowSpan 1 is default and omitted from metadata") {
        val meta =
            LayoutHintsBuilder().apply {
                col = 1
                rowSpan = 1
            }.toMetadata()
        meta shouldNotContainKey LayoutMetadataKeys.GRID_ROW_SPAN
    }

    test("rowSpan greater than 1 is included in metadata") {
        val meta = LayoutHintsBuilder().apply { rowSpan = 3 }.toMetadata()
        meta[LayoutMetadataKeys.GRID_ROW_SPAN] shouldBe KumlMetaValue.Integer(3L)
    }

    // ── 3. Relative constraints materialize as Items ───────────────────────────

    test("relative constraints materialize as Items with correct entries") {
        val builder =
            LayoutHintsBuilder().apply {
                above("A")
                rightOf("B")
            }
        val meta = builder.toMetadata()

        val items = meta[LayoutMetadataKeys.RELATIVE]
        items.shouldBeInstanceOf<KumlMetaValue.Items>()
        items.value shouldHaveSize 2

        val aboveEntry = items.value[0]
        aboveEntry.shouldBeInstanceOf<KumlMetaValue.Entries>()
        aboveEntry.value[LayoutMetadataKeys.REL_KIND] shouldBe KumlMetaValue.Text("above")
        aboveEntry.value[LayoutMetadataKeys.REL_OTHER] shouldBe KumlMetaValue.Text("A")

        val rightOfEntry = items.value[1]
        rightOfEntry.shouldBeInstanceOf<KumlMetaValue.Entries>()
        rightOfEntry.value[LayoutMetadataKeys.REL_KIND] shouldBe KumlMetaValue.Text("rightOf")
        rightOfEntry.value[LayoutMetadataKeys.REL_OTHER] shouldBe KumlMetaValue.Text("B")
    }

    test("all six relative constraint kinds serialize correctly") {
        val builder =
            LayoutHintsBuilder().apply {
                above("A")
                below("B")
                leftOf("C")
                rightOf("D")
                sameRowAs("E")
                sameColAs("F")
            }
        val items = builder.toMetadata()[LayoutMetadataKeys.RELATIVE] as KumlMetaValue.Items
        items.value shouldHaveSize 6

        val kinds = items.value.map { (it as KumlMetaValue.Entries).value[LayoutMetadataKeys.REL_KIND] }
        kinds[0] shouldBe KumlMetaValue.Text("above")
        kinds[1] shouldBe KumlMetaValue.Text("below")
        kinds[2] shouldBe KumlMetaValue.Text("leftOf")
        kinds[3] shouldBe KumlMetaValue.Text("rightOf")
        kinds[4] shouldBe KumlMetaValue.Text("sameRowAs")
        kinds[5] shouldBe KumlMetaValue.Text("sameColAs")
    }

    // ── 4. HasId overload and String overload are equivalent ──────────────────

    test("HasId overload and String overload produce identical Items") {
        val handleWithIdX =
            object : HasId {
                override val id = "X"
            }

        val builderWithHandle =
            LayoutHintsBuilder().apply {
                rightOf(handleWithIdX)
            }
        val builderWithString =
            LayoutHintsBuilder().apply {
                rightOf("X")
            }

        builderWithHandle.toMetadata() shouldBe builderWithString.toMetadata()
    }

    test("UmlClass model-type overload produces same result as String overload") {
        val cls = UmlClass(id = "MyClass", name = "MyClass")

        val withHandle =
            LayoutHintsBuilder()
                .apply { rightOf(cls) }
                .toMetadata()
        val withString =
            LayoutHintsBuilder()
                .apply { rightOf("MyClass") }
                .toMetadata()

        withHandle shouldBe withString
    }

    test("C4Person model-type overload produces same result as String overload") {
        val person = C4Person(id = "person-1", name = "Alice")

        val withHandle =
            LayoutHintsBuilder()
                .apply { below(person) }
                .toMetadata()
        val withString =
            LayoutHintsBuilder()
                .apply { below("person-1") }
                .toMetadata()

        withHandle shouldBe withString
    }

    // ── 5. Round-trip through JSON preserves layout metadata ──────────────────

    test("round trip through JSON preserves layout metadata on UmlClass") {
        // Build the UmlClass directly with layout metadata (UmlClass is @Serializable)
        val original =
            UmlClass(
                id = "Order",
                name = "Order",
                metadata =
                    LayoutHintsBuilder()
                        .apply {
                            col = 2
                            row = 1
                            colSpan = 2
                            pinned = true
                            rightOf("Customer")
                        }.toMetadata(),
            )

        val json = Json.encodeToString(original)
        val decoded: UmlClass = Json.decodeFromString(json)

        decoded.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(2L)
        decoded.metadata[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(1L)
        decoded.metadata[LayoutMetadataKeys.GRID_COL_SPAN] shouldBe KumlMetaValue.Integer(2L)
        decoded.metadata[LayoutMetadataKeys.PINNED] shouldBe KumlMetaValue.Flag(true)
        val items = decoded.metadata[LayoutMetadataKeys.RELATIVE] as KumlMetaValue.Items
        items.value shouldHaveSize 1
    }

    test("toMetadata is idempotent") {
        val builder =
            LayoutHintsBuilder().apply {
                col = 3
                row = 2
            }
        val first = builder.toMetadata()
        val second = builder.toMetadata()
        first shouldBe second
    }

    // ── Smoke tests: one per builder class ───────────────────────────────────

    test("smoke: ClassBuilder layout hint stored on UmlClass") {
        val model =
            umlModel("M") {
                classOf("Order") {
                    layout { col = 1 }
                }
            }
        val diagram = model.root as dev.kuml.core.model.KumlDiagram
        val cls = diagram.elements.filterIsInstance<UmlClass>().first()
        cls.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(1L)
    }

    test("smoke: ClassBuilder without layout block has empty metadata") {
        val model =
            umlModel("M") {
                classOf("Order")
            }
        val diagram = model.root as dev.kuml.core.model.KumlDiagram
        val cls = diagram.elements.filterIsInstance<UmlClass>().first()
        cls.metadata.shouldBeEmpty()
    }

    test("smoke: InterfaceBuilder layout hint stored on UmlInterface") {
        val model =
            umlModel("M") {
                interfaceOf("IService") {
                    layout {
                        col = 3
                        row = 2
                    }
                }
            }
        val diagram = model.root as dev.kuml.core.model.KumlDiagram
        val iface = diagram.elements.filterIsInstance<UmlInterface>().first()
        iface.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(3L)
        iface.metadata[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(2L)
    }

    test("smoke: EnumerationBuilder layout hint stored on UmlEnumeration") {
        val model =
            umlModel("M") {
                enumOf("Status") {
                    literal("ACTIVE")
                    layout {
                        col = 2
                        pinned = true
                    }
                }
            }
        val diagram = model.root as dev.kuml.core.model.KumlDiagram
        val enum = diagram.elements.filterIsInstance<UmlEnumeration>().first()
        enum.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(2L)
        enum.metadata[LayoutMetadataKeys.PINNED] shouldBe KumlMetaValue.Flag(true)
    }

    test("smoke: ComponentBuilder layout hint stored on UmlComponent") {
        val diagram =
            componentDiagram("Arch") {
                component("OrderService") {
                    layout {
                        col = 1
                        row = 2
                    }
                }
            }
        val comp = diagram.elements.filterIsInstance<UmlComponent>().first()
        comp.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(1L)
        comp.metadata[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(2L)
    }

    test("smoke: ActorBuilder layout hint stored on UmlActor") {
        val diagram =
            useCaseDiagram("UC") {
                actor("Customer") {
                    layout {
                        col = 1
                        row = 1
                    }
                }
            }
        val actor = diagram.elements.filterIsInstance<UmlActor>().first()
        actor.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(1L)
        actor.metadata[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(1L)
    }

    test("smoke: UseCaseBuilder layout hint stored on UmlUseCase") {
        val diagram =
            useCaseDiagram("UC") {
                useCase("Place Order") {
                    layout {
                        col = 2
                        row = 1
                    }
                }
            }
        val uc = diagram.elements.filterIsInstance<UmlUseCase>().first()
        uc.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(2L)
    }

    test("smoke: StateBodyBuilder layout hint stored on UmlState") {
        val diagram =
            stateDiagram("OrderSM") {
                state("Draft") {
                    layout {
                        col = 1
                        row = 2
                    }
                }
            }
        val sm = diagram.elements.filterIsInstance<UmlStateMachine>().first()
        val state = sm.vertices.filterIsInstance<UmlState>().first()
        state.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(1L)
        state.metadata[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(2L)
    }

    test("smoke: C4 PersonScope layout hint stored on C4Person") {
        val model =
            c4Model("Shop") {
                person("Customer") {
                    layout {
                        col = 1
                        row = 1
                    }
                }
            }
        val person = model.elements.filterIsInstance<C4Person>().first()
        person.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(1L)
        person.metadata[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(1L)
    }

    test("smoke: SoftwareSystemScope layout hint stored on C4SoftwareSystem") {
        val model =
            c4Model("Shop") {
                softwareSystem("Banking System") {
                    layout {
                        col = 2
                        row = 1
                    }
                }
            }
        val system = model.elements.filterIsInstance<C4SoftwareSystem>().first()
        system.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(2L)
    }

    test("smoke: ContainerScope layout hint stored on C4Container") {
        val model =
            c4Model("Shop") {
                softwareSystem("System") {
                    container("Web App") {
                        technology = "Spring Boot"
                        layout {
                            col = 2
                            row = 2
                        }
                    }
                }
            }
        val container = model.elements.filterIsInstance<C4Container>().first()
        container.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(2L)
        container.metadata[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(2L)
    }

    test("smoke: ComponentScope layout hint stored on C4Component") {
        val model =
            c4Model("Shop") {
                softwareSystem("System") {
                    container("API") {
                        component("Auth") {
                            layout {
                                col = 1
                                row = 3
                            }
                        }
                    }
                }
            }
        val component = model.elements.filterIsInstance<C4Component>().first()
        component.metadata[LayoutMetadataKeys.GRID_COL] shouldBe KumlMetaValue.Integer(1L)
        component.metadata[LayoutMetadataKeys.GRID_ROW] shouldBe KumlMetaValue.Integer(3L)
    }
})
