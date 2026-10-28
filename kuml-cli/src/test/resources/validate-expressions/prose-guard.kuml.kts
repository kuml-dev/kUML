@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

// A2 — free-text guard (prose) and a free-text effect: neither is parsed by `validate --strict`.
sysml2Model(name = "ProseGuards") {
    val a = stateDef(name = "A")
    val b = stateDef(name = "B")
    transition(
        name = "t1",
        source = a,
        target = b,
        trigger = "go",
        guard = prose(text = "Door is open and window closed"),
        effect = "Turn the light on",
    )
    stmDiagram(name = "d") {
        include(a)
        include(b)
    }
}
