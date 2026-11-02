classDiagram(name = "Formatted notes") {
    val order = classOf(name = "Order") {
        attribute(name = "id", type = "String")
    }
    val line = classOf(name = "OrderLine") {
        attribute(name = "qty", type = "Int")
    }
    association(source = order, target = line)
    comment(text = "A plain control note, <b>soon</b> & 2*3*4 and snake_case.", order, kind = NoteKind.NOTE)
    comment(text = "**Bold** and *italic* with `code()` and ***both***.", order, kind = NoteKind.NOTE)
    comment(text = "Checks:\n- totals are **rounded**\n- ids are *unique*\n- see `validate()`", order, kind = NoteKind.WARNING)
    comment(text = "Steps:\n1. validate\n1. reserve stock\n1. charge the card", order, kind = NoteKind.TODO)
    comment(text = "Read the [kUML handbook](https://kuml.dev/handbook) before you start, or the [very long link text that wraps over several lines](https://kuml.dev/a/long/path).", line, kind = NoteKind.INFO)
    comment(text = "Not a tag: <script>alert(1)</script> & [x](javascript:alert(1)) and [y](https://e.x/\"onmouseover=\"a)", line, kind = NoteKind.ERROR)
}
