package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class ErmContentSizeProviderTest :
    FunSpec({

        val baseline =
            ErmEntity(
                id = "base",
                name = "Base",
                attributes = listOf(ErmAttribute(id = "b1", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
            )
        val hub =
            ErmEntity(
                id = "hub",
                name = "Hub",
                attributes = listOf(ErmAttribute(id = "h1", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
            )
        val spoke1 =
            ErmEntity(
                id = "s1",
                name = "S1",
                attributes = listOf(ErmAttribute(id = "s1id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
            )
        val spoke2 =
            ErmEntity(
                id = "s2",
                name = "S2",
                attributes = listOf(ErmAttribute(id = "s2id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
            )
        val spoke3 =
            ErmEntity(
                id = "s3",
                name = "S3",
                attributes = listOf(ErmAttribute(id = "s3id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
            )

        fun rel(
            id: String,
            from: String,
            to: String,
        ) = ErmRelationship(
            id = id,
            name = null,
            sourceEntityId = from,
            targetEntityId = to,
            sourceCardinality = Cardinality.ONE,
            targetCardinality = Cardinality.ZERO_MANY,
        )

        val hubModel =
            ErmModel(
                name = "Hub",
                entities = listOf(baseline, hub, spoke1, spoke2, spoke3),
                relationships =
                    listOf(
                        rel("r1", "s1", "hub"),
                        rel("r2", "s2", "hub"),
                        rel("r3", "s3", "hub"),
                    ),
            )
        val diagram = ErmDiagram(name = "Hub")

        test("entity with no relationships gets no connection buffer (TopToBottom)") {
            val provider = ErmContentSizeProvider(hubModel, diagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf("base", "ErmEntity")
            (baseSize.width >= ErmContentSizeProvider.DEFAULT_W) shouldBe true
            (baseSize.height >= ErmContentSizeProvider.DEFAULT_H) shouldBe true
        }

        test("TopToBottom layout: hub entity grows horizontally by connections * CONNECTION_PUFFER_PX") {
            val provider = ErmContentSizeProvider(hubModel, diagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf("base", "ErmEntity")
            val hubSize = provider.sizeOf("hub", "ErmEntity")
            hubSize.width shouldBeGreaterThan baseSize.width
            hubSize.height shouldBe baseSize.height
            (hubSize.width - baseSize.width) shouldBe 3 * ErmContentSizeProvider.CONNECTION_PUFFER_PX
        }

        test("LeftToRight layout: hub entity grows vertically instead") {
            val provider = ErmContentSizeProvider(hubModel, diagram, LayoutDirection.LeftToRight)
            val baseSize = provider.sizeOf("base", "ErmEntity")
            val hubSize = provider.sizeOf("hub", "ErmEntity")
            hubSize.height shouldBeGreaterThan baseSize.height
            hubSize.width shouldBe baseSize.width
        }

        test("connection buffer is capped at CONNECTION_PUFFER_MAX_PX") {
            val spokes =
                (1..30).map {
                    ErmEntity(
                        id = "spoke$it",
                        name = "Spoke$it",
                        attributes = listOf(ErmAttribute(id = "spoke${it}id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
                    )
                }
            val relationships = spokes.map { rel("rel-${it.id}", it.id, "hub") }
            val megaModel = ErmModel(name = "MegaHub", entities = listOf(baseline, hub) + spokes, relationships = relationships)
            val provider = ErmContentSizeProvider(megaModel, diagram, LayoutDirection.TopToBottom)
            val baseSize = provider.sizeOf("base", "ErmEntity")
            val hubSize = provider.sizeOf("hub", "ErmEntity")
            (hubSize.width - baseSize.width) shouldBe ErmContentSizeProvider.CONNECTION_PUFFER_MAX_PX
        }

        test("a longer attribute line widens the box beyond DEFAULT_W") {
            val wideEntity =
                ErmEntity(
                    id = "wide",
                    name = "WideEntity",
                    attributes =
                        listOf(
                            ErmAttribute(id = "w1", name = "id", type = ErmDataType.Uuid, primaryKey = true),
                            ErmAttribute(
                                id = "w2",
                                name = "a_very_long_descriptive_column_name",
                                type = ErmDataType.Varchar(255),
                            ),
                        ),
                )
            val model = ErmModel(name = "Wide", entities = listOf(wideEntity))
            val provider = ErmContentSizeProvider(model, ErmDiagram(name = "Wide"))
            val size = provider.sizeOf("wide", "ErmEntity")
            size.width shouldBeGreaterThan ErmContentSizeProvider.DEFAULT_W
        }

        test("more attribute rows grow the box height") {
            val small =
                ErmEntity(
                    id = "small",
                    name = "Small",
                    attributes = listOf(ErmAttribute(id = "sm1", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
                )
            val many =
                ErmEntity(
                    id = "many",
                    name = "Many",
                    attributes =
                        listOf(ErmAttribute(id = "m1", name = "id", type = ErmDataType.Uuid, primaryKey = true)) +
                            (1..10).map { ErmAttribute(id = "attr$it", name = "col$it", type = ErmDataType.Integer()) },
                )
            val model = ErmModel(name = "Rows", entities = listOf(small, many))
            val provider = ErmContentSizeProvider(model, ErmDiagram(name = "Rows"))
            val smallSize = provider.sizeOf("small", "ErmEntity")
            val manySize = provider.sizeOf("many", "ErmEntity")
            manySize.height shouldBeGreaterThan smallSize.height
        }

        test("real ELK run: content-aware sizing is only visible through the bridge, not a hardcoded LayoutResult") {
            // CLAUDE.md pitfall guard: a hardcoded LayoutResult would never exercise
            // ErmContentSizeProvider at all. This test goes through the actual
            // ErmLayoutBridge → ELK pipeline to prove the size provider's output
            // really reaches the layout engine.
            val engine = ElkLayoutEngineProvider().engine()
            val sizeProvider = ErmContentSizeProvider(hubModel, diagram, LayoutHints.DEFAULT.direction)
            val graph = ErmLayoutBridge.toLayoutGraph(hubModel, diagram, sizeProvider)
            val layout = engine.layout(graph, LayoutHints.DEFAULT)

            val hubBounds =
                layout.nodes.entries
                    .first { it.key.value == "hub" }
                    .value.bounds
            val baseBounds =
                layout.nodes.entries
                    .first { it.key.value == "base" }
                    .value.bounds
            hubBounds.size.width shouldBeGreaterThan baseBounds.size.width
        }
    })
