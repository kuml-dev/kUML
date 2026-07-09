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
            val provider = UmlContentSizeProvider(hubDiagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf("Base", "UmlClass")
            // Bekanntes Baseline-Format: DEFAULT_W × DEFAULT_H für eine namenlose
            // Klasse — hier kommt 4-Buchstaben-Name + Defaults raus.
            (baseSize.width >= UmlContentSizeProvider.DEFAULT_W) shouldBe true
            (baseSize.height >= UmlContentSizeProvider.DEFAULT_H) shouldBe true
        }

        test("TopToBottom-Layout: Hub-Knoten wächst horizontal (Breite > Baseline)") {
            val provider = UmlContentSizeProvider(hubDiagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf("Base", "UmlClass")
            val hubSize = provider.sizeOf("Hub", "UmlClass")
            // 3 Kanten × 14 px = 42 px extra Breite, Höhe unverändert.
            hubSize.width shouldBeGreaterThan baseSize.width
            hubSize.height shouldBe baseSize.height
            (hubSize.width - baseSize.width) shouldBe 3 * UmlContentSizeProvider.CONNECTION_PUFFER_PX
        }

        test("LeftToRight-Layout: Hub-Knoten wächst vertikal (Höhe > Baseline)") {
            val provider = UmlContentSizeProvider(hubDiagram, LayoutDirection.LeftToRight)
            val baseSize = provider.sizeOf("Base", "UmlClass")
            val hubSize = provider.sizeOf("Hub", "UmlClass")
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
            val provider = UmlContentSizeProvider(megaHubDiagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf("Base", "UmlClass")
            val hubSize = provider.sizeOf("Hub", "UmlClass")
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
            val provider = UmlContentSizeProvider(selfishDiagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf("Base", "UmlClass")
            val selfSize = provider.sizeOf("Selfish", "UmlClass")
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
            val provider = UmlContentSizeProvider(genDiagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf("Base", "UmlClass")
            val childSize = provider.sizeOf("Child", "UmlClass")
            val parentSize = provider.sizeOf("Parent", "UmlClass")
            (childSize.width - baseSize.width) shouldBe UmlContentSizeProvider.CONNECTION_PUFFER_PX
            (parentSize.width - baseSize.width) shouldBe UmlContentSizeProvider.CONNECTION_PUFFER_PX
        }

        test("Default-Konstruktor nimmt TopToBottom an (Backward-Compat)") {
            val providerDefault = UmlContentSizeProvider(hubDiagram)
            val providerExplicit = UmlContentSizeProvider(hubDiagram, LayoutDirection.TopToBottom)
            providerDefault.sizeOf("Hub", "UmlClass") shouldBe providerExplicit.sizeOf("Hub", "UmlClass")
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
                                type = dev.kuml.uml.UmlTypeRef("String"),
                                stereotypes = stereotypes,
                            ),
                        ),
                )
            val withoutStereo = classWith(emptyList())
            val withStereo = classWith(listOf("Column"))
            val plainDiagram = KumlDiagram(name = "PlainStereo", elements = listOf(withoutStereo, withStereo))
            val provider = UmlContentSizeProvider(plainDiagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf(withoutStereo.id, "UmlClass")
            val stereoSize = provider.sizeOf(withStereo.id, "UmlClass")
            stereoSize.width shouldBeGreaterThan baseSize.width
        }
    })
