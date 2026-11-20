@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNote
import dev.kuml.erm.model.ErmNoteLink
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size

/** Hand-made ERM model and layout for the structural LaTeX tests (no layout engine). */
internal object ErmLatexFixtures {
    private fun rect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

    val customer =
        ErmEntity(
            id = "customer",
            name = "Customer_Account",
            attributes =
                listOf(
                    ErmAttribute(id = "customer.id", name = "id", type = ErmDataType.Uuid, primaryKey = true, nullable = false),
                    ErmAttribute(
                        id = "customer.mail",
                        name = "e_mail & co",
                        type = ErmDataType.Varchar(255),
                        unique = true,
                        nullable = false,
                    ),
                    ErmAttribute(id = "customer.disc", name = "discount_%", type = ErmDataType.Decimal(5, 2), default = "0"),
                ),
        )
    val order =
        ErmEntity(
            id = "order",
            name = "Order",
            attributes =
                listOf(
                    ErmAttribute(id = "order.id", name = "id", type = ErmDataType.Uuid, primaryKey = true, nullable = false),
                    ErmAttribute(
                        id = "order.cust",
                        name = "customer_id",
                        type = ErmDataType.Uuid,
                        foreignKey = ErmForeignKey(targetEntityId = "customer", targetAttributeId = "customer.id"),
                    ),
                ),
        )
    val item =
        ErmEntity(
            id = "item",
            name = "OrderItem",
            weak = true,
            attributes = listOf(ErmAttribute(id = "item.q", name = "quantity", type = ErmDataType.Integer(), nullable = false)),
        )

    fun model(
        noteHint: NotePlacement? = null,
        withNote: Boolean = false,
    ): ErmModel =
        ErmModel(
            name = "Shop",
            entities = listOf(customer, order, item),
            relationships =
                listOf(
                    ErmRelationship(
                        id = "r1",
                        name = "places",
                        sourceEntityId = "customer",
                        targetEntityId = "order",
                        sourceCardinality = Cardinality.ONE,
                        targetCardinality = Cardinality.ZERO_MANY,
                        sourceRole = "buyer",
                    ),
                    ErmRelationship(
                        id = "r2",
                        name = "contains",
                        sourceEntityId = "order",
                        targetEntityId = "item",
                        sourceCardinality = Cardinality.ZERO_ONE,
                        targetCardinality = Cardinality.ONE_MANY,
                        kind = RelationshipKind.IDENTIFYING,
                    ),
                ),
            diagrams = listOf(ErmDiagram(name = "Overview")),
            notes =
                if (withNote) {
                    listOf(
                        ErmNote(note = KumlNote(id = "n1", body = "Orders stay", placement = noteHint, sourceLine = 3)),
                    )
                } else {
                    emptyList()
                },
            noteLinks =
                if (withNote) listOf(ErmNoteLink(link = KumlNoteLink(id = "l1", noteId = "n1", targetId = "order"))) else emptyList(),
        )

    fun layout(withNote: Boolean = false): LayoutResult =
        LayoutResult(
            engineId = LayoutEngineId("test"),
            seed = null,
            canvas = Size(width = 700f, height = 500f),
            nodes =
                buildMap {
                    put(NodeId("customer"), NodeLayout(bounds = rect(20f, 20f, 200f, 140f)))
                    put(NodeId("order"), NodeLayout(bounds = rect(20f, 260f, 200f, 100f)))
                    put(NodeId("item"), NodeLayout(bounds = rect(400f, 260f, 200f, 80f)))
                    if (withNote) put(NodeId("n1"), NodeLayout(bounds = rect(400f, 20f, 140f, 40f)))
                },
            edges =
                buildMap {
                    // customer (bottom) -> order (top): vertical route, so the glyph directions are known.
                    put(EdgeId("r1"), EdgeRoute.Direct(source = Point(120f, 160f), target = Point(120f, 260f)))
                    // order (right) -> item (left): horizontal route.
                    put(
                        EdgeId("r2"),
                        EdgeRoute.OrthogonalRounded(
                            source = Point(220f, 310f),
                            target = Point(400f, 300f),
                            waypoints = listOf(Point(310f, 310f), Point(310f, 300f)),
                            cornerRadiusPx = 0f,
                        ),
                    )
                    if (withNote) put(EdgeId("l1"), EdgeRoute.Direct(source = Point(400f, 50f), target = Point(220f, 280f)))
                },
            groups = emptyMap(),
        )

    fun render(
        notation: dev.kuml.erm.model.ErmNotation = dev.kuml.erm.model.ErmNotation.MARTIN,
        options: LatexRenderOptions = LatexRenderOptions(),
        withNote: Boolean = false,
        noteHint: NotePlacement? = null,
        model: ErmModel = model(noteHint = noteHint, withNote = withNote),
    ): String =
        KumlLatexRenderer.toLatex(
            model = model,
            diagram = model.diagrams.first(),
            layoutResult = layout(withNote = withNote),
            notation = notation,
            options = options,
        )
}
