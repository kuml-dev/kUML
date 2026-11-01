import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PinSide
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("All2F") {
    val operator = activityPartition("Operator")
    val system = activityPartition("Gebäudeleittechnik und Zutrittssteuerung")
    val field = activityPartition("Feld")

    val start = initialNode(partition = operator)
    val report =
        actionDef(
            name = "Meldung erfassen",
            partition = operator,
            pins = listOf(ActionPin(name = "Störmeldung", typeId = "Meldung", direction = PinDirection.Output, side = PinSide.Right)),
        )
    val r = interruptibleRegion(name = "Bearbeitung")
    val wait = acceptEvent(name = "Rückmeldung der Gebäudeleittechnik zur Türöffnung.", partition = system, region = r)
    val work = actionDef(name = "Meldung bearbeiten", partition = system, region = r)
    val m = mergeNode(name = "M", partition = system)
    val a = actionDef(name = "Tür öffnen", partition = field)
    val g = actionDef(name = "Status melden", partition = field)
    val c1 = actionDef(name = "Quelle 1", partition = system)
    val c2 = actionDef(name = "Quelle 2", partition = system)
    val c3 = actionDef(name = "Quelle 3", partition = system)
    val c4 = actionDef(name = "Quelle 4", partition = system)
    val c5 = actionDef(name = "Quelle 5", partition = system)
    val c6 = actionDef(name = "Quelle 6", partition = system)
    val big = mergeNode(name = "Sammler", partition = system)
    val handler = actionDef(name = "Fehler behandeln", partition = system)
    val cancel = actionDef(name = "Abbrechen", partition = operator)
    val target =
        actionDef(
            name = "Ablegen",
            partition = field,
            pins = listOf(ActionPin(name = "Störmeldung", typeId = "Meldung", direction = PinDirection.Input, side = PinSide.Left)),
        )
    val end = finalNode(partition = operator)

    controlFlow(name = "go", source = start, target = report)
    controlFlow(name = "w", source = report, target = wait)
    controlFlow(name = "toWork", source = wait, target = work)
    controlFlow(name = "toMerge", source = work, target = m)
    controlFlow(name = "toDoor", source = m, target = a)
    controlFlow(name = "toStatus", source = a, target = g)
    controlFlow(name = "again", source = g, target = report, guard = "weiter")
    controlFlow(name = "interrupt", source = work, target = cancel, interrupts = true)
    objectFlow(name = "meldung", source = report, target = target, objectType = "Störmeldung", sourcePin = "Störmeldung", targetPin = "Störmeldung")
    for ((i, c) in listOf(c1, c2, c3, c4, c5, c6).withIndex()) {
        objectFlow(name = "o$i", source = c, target = big, objectType = "Störmeldung")
        controlFlow(name = "s$i", source = work, target = c)
    }
    controlFlow(name = "bigOut", source = big, target = target)
    controlFlow(name = "cancelEnd", source = cancel, target = end)
    exceptionHandler(protectedNode = work, handler = handler, type = "Fehler")
    controlFlow(name = "handled", source = handler, target = end)

    actDiagram("All 2F") {
        include(start)
        include(report)
        include(wait)
        include(work)
        include(m)
        include(a)
        include(g)
        include(c1)
        include(c2)
        include(c3)
        include(c4)
        include(c5)
        include(c6)
        include(big)
        include(handler)
        include(cancel)
        include(target)
        include(end)
        layout {
            sameLayer(a, handler)
        }
    }
}
