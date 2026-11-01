package dev.kuml.sysml2

/** Main flow direction of a diagram; since PAL wave 2B an alias of the shared [dev.kuml.core.model.layout.LayoutFlow]. */
typealias Sysml2LayoutFlow = dev.kuml.core.model.layout.LayoutFlow

/**
 * Layout steering hints of one SysML 2 diagram; since PAL wave 2B an alias of the shared
 * [dev.kuml.core.model.layout.LayoutSpec] (identical JSON form, new optional fields).
 */
typealias Sysml2LayoutSpec = dev.kuml.core.model.layout.LayoutSpec

/** Side of the subject frame the actors of a use-case diagram stand on (wave 2E-b); usable as `actorSide = ActorSide.Left`. */
typealias ActorSide = dev.kuml.core.model.layout.ActorSide
