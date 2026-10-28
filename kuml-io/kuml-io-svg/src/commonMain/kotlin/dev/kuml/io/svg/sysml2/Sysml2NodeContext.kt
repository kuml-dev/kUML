package dev.kuml.io.svg.sysml2

import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.projection.BddProjection

/**
 * Optional rendering context for SysML 2 nodes. Without it (direct calls from
 * unit tests, other diagram kinds) nodes render exactly as before.
 *
 * @property model the model, used to resolve port types and directions.
 * @property bdd BDD projection: which feature lines are replaced by edges and
 *   which extra compartments (`actions`, `signals`) a block shows.
 */
internal class Sysml2NodeContext(
    val model: Sysml2Model,
    val bdd: BddProjection? = null,
)
