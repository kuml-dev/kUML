@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCategory
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
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
import dev.kuml.layout.bridge.erm.ErmIdef1xLayoutBridge

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

    // ---- IDEF1X fixtures (Nacharbeit 126): a category cluster plus relationships covering Z, 1, P and blank ----

    private val retail =
        ErmEntity(
            id = "retail",
            name = "Retail",
            attributes = listOf(ErmAttribute(id = "retail.id", name = "id", type = ErmDataType.Uuid, primaryKey = true, nullable = false)),
        )
    private val wholesale =
        ErmEntity(
            id = "wholesale",
            name = "Wholesale",
            attributes =
                listOf(
                    ErmAttribute(id = "wholesale.id", name = "id", type = ErmDataType.Uuid, primaryKey = true, nullable = false),
                ),
        )

    /**
     * Customer with the category c1 (subtypes Retail, Wholesale), Order, a weak OrderItem and four relationships:
     * r1 (blank letter, mandatory parent), r2 (P, optional parent, identifying), r3 (Z, non-identifying),
     * r4 (1, optional parent, non-identifying).
     */
    fun categoryModel(
        complete: Boolean = true,
        discriminator: String? = "customer.disc",
    ): ErmModel =
        model().copy(
            entities = listOf(customer, order, item, retail, wholesale),
            relationships =
                model().relationships +
                    listOf(
                        ErmRelationship(
                            id = "r3",
                            name = null,
                            sourceEntityId = "order",
                            targetEntityId = "wholesale",
                            sourceCardinality = Cardinality.ONE,
                            targetCardinality = Cardinality.ZERO_ONE,
                        ),
                        ErmRelationship(
                            id = "r4",
                            name = null,
                            sourceEntityId = "item",
                            targetEntityId = "retail",
                            sourceCardinality = Cardinality.ZERO_ONE,
                            targetCardinality = Cardinality.ONE,
                        ),
                    ),
            categories =
                listOf(
                    ErmCategory(
                        id = "c1",
                        name = "Kind",
                        supertypeEntityId = "customer",
                        subtypeEntityIds = listOf("retail", "wholesale"),
                        complete = complete,
                        discriminatorAttributeId = discriminator,
                    ),
                ),
        )

    fun categoryLayout(withCategoryNode: Boolean = true): LayoutResult {
        val base = layout()
        val p = ErmIdef1xLayoutBridge
        return base.copy(
            nodes =
                base.nodes +
                    buildMap {
                        put(NodeId("retail"), NodeLayout(bounds = rect(300f, 20f, 100f, 60f)))
                        put(NodeId("wholesale"), NodeLayout(bounds = rect(450f, 20f, 100f, 60f)))
                        if (withCategoryNode) put(NodeId("${p.CATEGORY_NODE_PREFIX}c1"), NodeLayout(bounds = rect(250f, 90f, 24f, 24f)))
                        put(NodeId("${p.CATEGORY_NODE_PREFIX}ghost"), NodeLayout(bounds = rect(250f, 200f, 24f, 24f)))
                    },
            edges =
                base.edges +
                    mapOf(
                        EdgeId("r3") to EdgeRoute.Direct(source = Point(220f, 280f), target = Point(450f, 80f)),
                        EdgeId("r4") to EdgeRoute.Direct(source = Point(420f, 260f), target = Point(350f, 80f)),
                        EdgeId("${p.CATEGORY_EDGE_SUP_PREFIX}c1") to
                            EdgeRoute.Direct(source = Point(220f, 90f), target = Point(250f, 102f)),
                        EdgeId("${p.CATEGORY_EDGE_SUB_PREFIX}c1::retail") to
                            EdgeRoute.Direct(source = Point(274f, 102f), target = Point(350f, 80f)),
                        EdgeId("${p.CATEGORY_EDGE_SUB_PREFIX}c1::wholesale") to
                            EdgeRoute.Direct(source = Point(274f, 102f), target = Point(500f, 80f)),
                    ),
        )
    }

    // ---- Self-reference fixture (Nacharbeit 127): Employee "manages" Employee, with ELK's raw cramped U-shape ----

    val employee =
        ErmEntity(
            id = "employee",
            name = "Employee",
            attributes =
                listOf(
                    ErmAttribute(id = "employee.id", name = "id", type = ErmDataType.Uuid, primaryKey = true, nullable = false),
                    ErmAttribute(id = "employee.mgr", name = "manager_id", type = ErmDataType.Uuid),
                ),
        )

    /** Layout-space geometry of the self-reference entity, shared by the tests. */
    val employeeBounds: Rect = rect(20f, 20f, 200f, 100f)

    fun selfReferenceModel(): ErmModel =
        ErmModel(
            name = "Staff",
            entities = listOf(employee),
            relationships =
                listOf(
                    ErmRelationship(
                        id = "r1",
                        name = "manages",
                        sourceEntityId = "employee",
                        targetEntityId = "employee",
                        sourceCardinality = Cardinality.ZERO_ONE,
                        targetCardinality = Cardinality.ZERO_MANY,
                        sourceRole = "manager",
                        targetRole = "report",
                    ),
                ),
            diagrams = listOf(ErmDiagram(name = "Overview")),
        )

    fun selfReferenceLayout(): LayoutResult =
        LayoutResult(
            engineId = LayoutEngineId("test"),
            seed = null,
            canvas = Size(width = 300f, height = 160f),
            nodes = mapOf(NodeId("employee") to NodeLayout(bounds = employeeBounds)),
            edges =
                mapOf(
                    // ELK's raw route: a ~10 px U-shape hugging the left side of the box.
                    EdgeId("r1") to
                        EdgeRoute.OrthogonalRounded(
                            source = Point(20f, 45f),
                            target = Point(20f, 95f),
                            waypoints = listOf(Point(10f, 45f), Point(10f, 95f)),
                            cornerRadiusPx = 0f,
                        ),
                ),
            groups = emptyMap(),
        )

    /** Nacharbeit 129: the same self-reference, but another edge ends on the right border and there is room on the left. */
    val employeeBoundsRightBusy: Rect = rect(120f, 20f, 200f, 100f)

    fun selfReferenceRightBusyModel(): ErmModel {
        val department = employee.copy(id = "department", name = "Department")
        val base = selfReferenceModel()
        return base.copy(
            entities = listOf(employee, department),
            relationships =
                base.relationships +
                    ErmRelationship(
                        id = "r2",
                        name = "works in",
                        sourceEntityId = "employee",
                        targetEntityId = "department",
                        sourceCardinality = Cardinality.ONE,
                        targetCardinality = Cardinality.ZERO_MANY,
                    ),
        )
    }

    fun selfReferenceRightBusyLayout(): LayoutResult =
        LayoutResult(
            engineId = LayoutEngineId("test"),
            seed = null,
            canvas = Size(width = 600f, height = 160f),
            nodes =
                mapOf(
                    NodeId("employee") to NodeLayout(bounds = employeeBoundsRightBusy),
                    NodeId("department") to NodeLayout(bounds = rect(460f, 20f, 120f, 100f)),
                ),
            edges =
                mapOf(
                    EdgeId("r1") to
                        EdgeRoute.OrthogonalRounded(
                            source = Point(120f, 45f),
                            target = Point(120f, 95f),
                            waypoints = listOf(Point(110f, 45f), Point(110f, 95f)),
                            cornerRadiusPx = 0f,
                        ),
                    EdgeId("r2") to EdgeRoute.Direct(source = Point(320f, 70f), target = Point(460f, 70f)),
                ),
            groups = emptyMap(),
        )

    fun renderSelfReferenceRightBusy(notation: ErmNotation): String {
        val model = selfReferenceRightBusyModel()
        return KumlLatexRenderer.toLatex(
            model = model,
            diagram = model.diagrams.first(),
            layoutResult = selfReferenceRightBusyLayout(),
            notation = notation,
            options = LatexRenderOptions(),
        )
    }

    fun renderSelfReference(
        notation: ErmNotation,
        model: ErmModel = selfReferenceModel(),
    ): String =
        KumlLatexRenderer.toLatex(
            model = model,
            diagram = model.diagrams.first(),
            layoutResult = selfReferenceLayout(),
            notation = notation,
            options = LatexRenderOptions(),
        )

    fun renderIdef1x(
        model: ErmModel = categoryModel(),
        layout: LayoutResult = categoryLayout(),
        options: LatexRenderOptions = LatexRenderOptions(),
    ): String =
        KumlLatexRenderer.toLatex(
            model = model,
            diagram = model.diagrams.first(),
            layoutResult = layout,
            notation = ErmNotation.IDEF1X,
            options = options,
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
