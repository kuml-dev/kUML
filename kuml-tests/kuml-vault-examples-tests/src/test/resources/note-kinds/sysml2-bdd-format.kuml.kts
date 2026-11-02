sysml2Model("Formatted notes") {
    val vehicle = partDef("Vehicle")
    val engine = partDef("Engine")
    bdd("Formatted notes") {
        include(vehicle)
        include(engine)
        note("A plain control note with 0..* and 2*3*4.", vehicle, kind = NoteKind.NOTE)
        comment("**Requirement:** the *engine* needs `12V`.\n3. start\n3. run\n- escaped \\* star and C:\\Users", vehicle, kind = NoteKind.INFO)
        comment("Risk: ***fatal overheating*** is possible, see [the spec](https://example.org/spec).", engine, kind = NoteKind.RISK)
        comment("Not a link: [z](vbscript:x) and <b>raw</b> markup.", engine, kind = NoteKind.WARNING)
    }
}
