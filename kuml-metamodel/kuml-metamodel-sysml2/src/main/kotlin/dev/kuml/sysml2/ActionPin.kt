package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * One input or output pin on an [ActionDefinition] — the typed-port record on
 * the action's edge (V2.0.16).
 *
 * Pins are intentionally **NOT** [Sysml2Element]s: they are not top-level
 * addressable objects in the model. They are sub-records owned by their parent
 * [ActionDefinition] (analogous to enum members owned by their enum and
 * mirroring the precedent set by [ConstraintParameter] in V2.0.12), and their
 * addressable id is the synthetic concatenation `"<actionId>::<pinName>"`
 * used as the endpoint id when [ObjectFlowUsage] endpoint-anchoring lands
 * (V2.x). Keeping pins as a flat data class avoids an extra registry slot in
 * [Sysml2Model] for what is essentially a leaf struct.
 *
 * Carries three slots:
 *  - [name] — the local pin name within the action, e.g. `"orderDetails"`,
 *    `"validation"`. Used as the pin label adjacent to the small square the
 *    renderer draws on the action box edge and as the second half of the
 *    synthetic endpoint id (`"ValidateOrder::orderDetails"`).
 *  - [typeId] — optional reference to an [AttributeDefinition] /
 *    [PartDefinition] / DataType id. The V2.0.16 MVP uses the typeId only
 *    as a tooltip / metadata hint (the renderer does not surface it in the
 *    pin label — keeps the action box compact); a typed-link to the
 *    referenced definition is V2.x polish, identical reasoning to
 *    [ConstraintParameter.typeId].
 *  - [direction] — [PinDirection] discriminator. Defaults to [PinDirection.Input]
 *    so an under-specified pin is silently treated as consuming a token
 *    (matches the most common case: an action consumes its arguments).
 *
 * V2.0.16 MVP scope:
 *  - Plain data record; no specialisation, no nesting, no multi-valued pins.
 *  - Multiplicity is implicitly `1..1` — multi-valued pins are V2.x polish.
 *  - typeId-resolution is renderer / validator's responsibility; no
 *    metamodel-side cross-reference checking.
 *  - Pin geometry is purely a rendering decoration on the Action box edge —
 *    `ObjectFlowUsage` endpoints still anchor on the Action node centre.
 *    Endpoint-pin-anchoring (drawing the edge directly to the pin position)
 *    is V2.x, identical limitation to the V2.0.12 binding-connector
 *    parameter-pin-anchor on PAR.
 */
@Serializable
public data class ActionPin(
    /** Local name within the action — e.g. `"orderDetails"`, `"validation"`. */
    val name: String,
    /**
     * Optional type reference — typically an [AttributeDefinition] id (e.g.
     * `"Order"`) or a [PartDefinition] id. Surfaces as tooltip / metadata in
     * the V2.0.16 MVP; rendered in the pin label is V2.x polish.
     */
    val typeId: String? = null,
    /** Input (left edge, consumed) or Output (right edge, produced). */
    val direction: PinDirection = PinDirection.Input,
)
