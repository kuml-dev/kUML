package dev.kuml.uml.dsl

import dev.kuml.core.dsl.layout.DiagramLayoutScope
import dev.kuml.core.dsl.layout.UmlLayoutBuilder

/**
 * Layout steering hints for this diagram (PAL wave 2B), e.g. `layout { wrapColumns = 3 }`.
 *
 * Deliberately an *extension* and not a member of [DiagramLayoutScope]: inside `classOf { layout { col = 2 } }`
 * a member of the (DSL-marker-hidden) outer diagram builder would win the overload resolution and then be
 * rejected, instead of letting the element-level `LayoutHintsScope.layout` apply. It lives in
 * `dev.kuml.uml.dsl`, which every kUML script imports by default.
 */
public fun DiagramLayoutScope.layout(block: UmlLayoutBuilder.() -> Unit) {
    layoutCollector.add(UmlLayoutBuilder().apply(block).build())
}
