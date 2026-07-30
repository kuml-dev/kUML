package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.LayoutDirection
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * Tests für die V2.x-"Connection-aware Sizing"-Heuristik in
 * [UmlContentSizeProvider]: Knoten mit vielen ein-/ausgehenden Kanten wachsen
 * auf der Seite, an der ELK voraussichtlich die Kanten andocken lässt.
 */
class UmlContentSizeProviderTest :
    FunSpec({

        // Baseline class — no connections, used to read out the un-puffered size.
        val baseline = UmlClass(id = "Base", name = "Base")

        // Hub class — same content as baseline, but many incoming associations.
        val hub = UmlClass(id = "Hub", name = "Hub")
        val spoke1 = UmlClass(id = "S1", name = "S1")
        val spoke2 = UmlClass(id = "S2", name = "S2")
        val spoke3 = UmlClass(id = "S3", name = "S3")

        fun assoc(
            id: String,
            sourceId: String,
            targetId: String,
        ): UmlAssociation =
            UmlAssociation(
                id = id,
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = sourceId),
                        UmlAssociationEnd(typeId = targetId),
                    ),
            )

        val hubDiagram =
            KumlDiagram(
                name = "Hub",
                elements =
                    listOf(
                        baseline,
                        hub,
                        spoke1,
                        spoke2,
                        spoke3,
                        assoc("a1", "S1", "Hub"),
                        assoc("a2", "S2", "Hub"),
                        assoc("a3", "S3", "Hub"),
                    ),
            )

        test("Knoten ohne Kanten bekommt keinen Anschluss-Puffer (TopToBottom)") {
            val provider = UmlContentSizeProvider(diagram = hubDiagram, layoutDirection = LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf(elementId = "Base", elementKind = "UmlClass")
            // Bekanntes Baseline-Format: DEFAULT_W × DEFAULT_H für eine namenlose
            // Klasse — hier kommt 4-Buchstaben-Name + Defaults raus.
            (baseSize.width >= UmlContentSizeProvider.DEFAULT_W) shouldBe true
            (baseSize.height >= UmlContentSizeProvider.DEFAULT_H) shouldBe true
        }

        test("TopToBottom-Layout: Hub-Knoten wächst horizontal (Breite > Baseline)") {
            val provider = UmlContentSizeProvider(diagram = hubDiagram, layoutDirection = LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf(elementId = "Base", elementKind = "UmlClass")
            val hubSize = provider.sizeOf(elementId = "Hub", elementKind = "UmlClass")
            // 3 Kanten × 14 px = 42 px extra Breite, Höhe unverändert.
            hubSize.width shouldBeGreaterThan baseSize.width
            hubSize.height shouldBe baseSize.height
            (hubSize.width - baseSize.width) shouldBe 3 * UmlContentSizeProvider.CONNECTION_PUFFER_PX
        }

        test("LeftToRight-Layout: Hub-Knoten wächst vertikal (Höhe > Baseline)") {
            val provider = UmlContentSizeProvider(diagram = hubDiagram, layoutDirection = LayoutDirection.LeftToRight)
            val baseSize = provider.sizeOf(elementId = "Base", elementKind = "UmlClass")
            val hubSize = provider.sizeOf(elementId = "Hub", elementKind = "UmlClass")
            // 3 Kanten × 14 px = 42 px extra Höhe, Breite unverändert.
            hubSize.height shouldBeGreaterThan baseSize.height
            hubSize.width shouldBe baseSize.width
            (hubSize.height - baseSize.height) shouldBe 3 * UmlContentSizeProvider.CONNECTION_PUFFER_PX
        }

        test("Puffer ist auf CONNECTION_PUFFER_MAX_PX gedeckelt") {
            // 30 Kanten würden roh 420 px ergeben — der Deckel greift bei 200 px.
            val spokes = (1..30).map { UmlClass(id = "S$it", name = "S$it") }
            val assocs = spokes.map { assoc(id = "a-${it.id}", sourceId = it.id, targetId = "Hub") }
            val megaHubDiagram =
                KumlDiagram(
                    name = "MegaHub",
                    elements = listOf(baseline, hub) + spokes + assocs,
                )
            val provider = UmlContentSizeProvider(diagram = megaHubDiagram, layoutDirection = LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf(elementId = "Base", elementKind = "UmlClass")
            val hubSize = provider.sizeOf(elementId = "Hub", elementKind = "UmlClass")
            (hubSize.width - baseSize.width) shouldBe UmlContentSizeProvider.CONNECTION_PUFFER_MAX_PX
        }

        test("Self-Loops zählen als 2 Kanten") {
            val selfish = UmlClass(id = "Selfish", name = "Selfish")
            val selfishDiagram =
                KumlDiagram(
                    name = "Selfish",
                    elements =
                        listOf(
                            baseline,
                            selfish,
                            assoc("self", "Selfish", "Selfish"),
                        ),
                )
            val provider = UmlContentSizeProvider(diagram = selfishDiagram, layoutDirection = LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf(elementId = "Base", elementKind = "UmlClass")
            val selfSize = provider.sizeOf(elementId = "Selfish", elementKind = "UmlClass")
            // 2 Endpunkte derselben Self-Loop-Association → 2 × 14 px = 28 px.
            (selfSize.width - baseSize.width) shouldBe 2 * UmlContentSizeProvider.CONNECTION_PUFFER_PX
        }

        test("Generalization-Kante zählt wie eine normale Assoziation") {
            val child = UmlClass(id = "Child", name = "Child")
            val parent = UmlClass(id = "Parent", name = "Parent")
            val genDiagram =
                KumlDiagram(
                    name = "Gen",
                    elements =
                        listOf(
                            baseline,
                            child,
                            parent,
                            UmlGeneralization(id = "g1", specificId = "Child", generalId = "Parent"),
                        ),
                )
            val provider = UmlContentSizeProvider(diagram = genDiagram, layoutDirection = LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf(elementId = "Base", elementKind = "UmlClass")
            val childSize = provider.sizeOf(elementId = "Child", elementKind = "UmlClass")
            val parentSize = provider.sizeOf(elementId = "Parent", elementKind = "UmlClass")
            (childSize.width - baseSize.width) shouldBe UmlContentSizeProvider.CONNECTION_PUFFER_PX
            (parentSize.width - baseSize.width) shouldBe UmlContentSizeProvider.CONNECTION_PUFFER_PX
        }

        test("Default-Konstruktor nimmt TopToBottom an (Backward-Compat)") {
            val providerDefault = UmlContentSizeProvider(diagram = hubDiagram)
            val providerExplicit = UmlContentSizeProvider(diagram = hubDiagram, layoutDirection = LayoutDirection.TopToBottom)
            providerDefault.sizeOf(elementId = "Hub", elementKind = "UmlClass") shouldBe
                providerExplicit.sizeOf(elementId = "Hub", elementKind = "UmlClass")
        }

        // ADR-0017: plain display-label stereotype on an attribute (`stereotypes += "Column"`,
        // no profile/appliedStereotypes involved) must widen the class box just like an
        // appliedStereotype does — otherwise StereotypeHelper.featureStereotypeTspan() renders
        // a «Column» prefix the layout never made room for, and the line overflows the box.
        // Isolate the effect: same class name, same attribute name/type, only the stereotype
        // prefix differs between the two classes.
        test("Attribut mit plain Stereotyp verbreitert die Klassenbox gegenüber gleicher Klasse ohne Stereotyp") {
            fun classWith(stereotypes: List<String>) =
                UmlClass(
                    id = "C-${stereotypes.size}",
                    name = "WithPlainStereo",
                    attributes =
                        listOf(
                            dev.kuml.uml.UmlProperty(
                                id = "attr-${stereotypes.size}",
                                name = "name",
                                type = dev.kuml.uml.UmlTypeRef(name = "String"),
                                stereotypes = stereotypes,
                            ),
                        ),
                )
            val withoutStereo = classWith(emptyList())
            val withStereo = classWith(listOf("Column"))
            val plainDiagram = KumlDiagram(name = "PlainStereo", elements = listOf(withoutStereo, withStereo))
            val provider = UmlContentSizeProvider(diagram = plainDiagram, layoutDirection = LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf(elementId = withoutStereo.id, elementKind = "UmlClass")
            val stereoSize = provider.sizeOf(elementId = withStereo.id, elementKind = "UmlClass")
            stereoSize.width shouldBeGreaterThan baseSize.width
        }
    })
