package dev.kuml.io.svg.sysml2

import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.projection.BddProjection

/**
 * Optional rendering context for SysML 2 nodes. Without it (direct calls from
 * unit tests, other diagram kinds) nodes render exactly as before.
 *
 * @property model the model, used to resolve port types and directions.
 * @property bdd BDD projection: which feature lines are replaced by edges and
 *   which extra compartments (`actions`, `flows`) a block shows.
 */
internal class Sysml2NodeContext(
    val model: Sysml2Model,
    val bdd: BddProjection? = null,
    /** IBD only: draw the attribute compartment of part boxes (PAL wave 2C-b2). */
    val ibdShowAttributes: Boolean = false,
    /** IBD only: the nested part frames (box in box) of all part boxes; every frame names its part box (PAL wave 2C-b2b). */
    val ibdFrames: List<dev.kuml.layout.IbdNestedFrame> = emptyList(),
    /**
     * IBD only: the planned rectangle of each part-port label, local to its part box, keyed `<node id>::<port key>`
     * (PAL wave 2C-b2b). A port without an entry is drawn with the bridge's fixed placement.
     */
    val ibdPortLabels: Map<String, dev.kuml.layout.Rect> = emptyMap(),
    /** UC only (wave 2E-b): extension points per use-case id, drawn as a block inside the ellipse. */
    val ucExtensionPoints: Map<String, List<String>> = emptyMap(),
)
