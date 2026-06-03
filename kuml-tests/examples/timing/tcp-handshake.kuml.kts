// tcp-handshake.kuml.kts — UML 2.x timing diagram (V1.1).
// Classic three-way TCP handshake: client transitions
// CLOSED → SYN_SENT → ESTABLISHED while the server transitions
// LISTEN → SYN_RECEIVED → ESTABLISHED.

timingDiagram(name = "TCP three-way handshake") {
    lifeline(name = "client", states = listOf("CLOSED", "SYN_SENT", "ESTABLISHED")) {
        tick(t = 0, state = "CLOSED")
        tick(t = 1, state = "SYN_SENT")
        tick(t = 4, state = "ESTABLISHED")
    }
    lifeline(name = "server", states = listOf("LISTEN", "SYN_RECEIVED", "ESTABLISHED")) {
        tick(t = 0, state = "LISTEN")
        tick(t = 2, state = "SYN_RECEIVED")
        tick(t = 4, state = "ESTABLISHED")
    }
}
