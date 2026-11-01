import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "SeqFragmentsTime") {
    val person = lifelineDef(name = "Person")
    val vitec = lifelineDef(name = "Vitec")
    val fmcs = lifelineDef(name = "FMCS")
    val log = lifelineDef(name = "Log")

    foundMessage(label = "Tür öffnen", target = vitec, seqNo = 0, source = person)
    foundMessage(label = "Alarm", target = fmcs, seqNo = 1)
    combinedFragment(name = "outer", operator = CombinedFragmentOperator.Loop) {
        operand(guard = "solange offen", start = 2, end = 6) {
            combinedFragment(name = "inner", operator = CombinedFragmentOperator.Alt) {
                operand(guard = "berechtigt", start = 3, end = 4)
                operand(guard = "sonst", start = 5, end = 6)
            }
        }
    }
    message(label = "prüfen", source = vitec, target = fmcs, seqNo = 2, kind = MessageKind.Sync)
    message(label = "freigeben", source = fmcs, target = vitec, seqNo = 3, kind = MessageKind.Reply)
    message(label = "öffnen", source = vitec, target = fmcs, seqNo = 4, kind = MessageKind.Sync)
    message(label = "ablehnen", source = vitec, target = person, seqNo = 5, kind = MessageKind.Reply)
    message(label = "protokollieren", source = vitec, target = vitec, seqNo = 6, kind = MessageKind.Sync)
    combinedFragment(name = "refOnly", operator = CombinedFragmentOperator.Opt) {
        operand(guard = "bei Bedarf", start = 7, end = 7)
    }
    interactionUse(name = "audit", refersTo = "AuditTrail", lifelines = listOf(vitec), seqNo = 7)
    combinedFragment(name = "parallel", operator = CombinedFragmentOperator.Par) {
        operand(guard = "", start = 8, end = 8)
        operand(guard = "", start = 9, end = 9)
    }
    message(label = "melden", source = vitec, target = log, seqNo = 8, kind = MessageKind.Async)
    message(label = "zählen", source = fmcs, target = log, seqNo = 9, kind = MessageKind.Async)
    lostMessage(label = "Verlust", source = log, seqNo = 10)
    timeObservation(lifeline = vitec, seqNo = 2, name = "tGeschlossen")
    timeObservation(lifeline = fmcs, seqNo = 3, name = "tOffen", label = "now")
    durationConstraint(lifeline = vitec, from = 6, to = 6, expression = "d <= 1 s")

    seqDiagram(name = "Fragments and time") {
        include(person)
        include(vitec)
        include(fmcs)
        include(log)
    }
}
