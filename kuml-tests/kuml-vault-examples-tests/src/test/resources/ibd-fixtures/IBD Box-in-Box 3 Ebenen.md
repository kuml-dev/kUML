# IBD Box-in-Box, drei Ebenen (Testfixture)

Fixture fuer `Sysml2BoxInBoxTest`. Liegt bewusst ausserhalb von `vault-examples/`, weil
`scripts/sync-vault-examples.sh` diesen Ordner mit `rsync --delete` spiegelt.

```kuml
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.ConnectionLineStyle
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.units.kg

sysml2Model(name = "Plant") {
    val feed = connectionDef(name = "Feed", style = ConnectionLineStyle.DASHED, color = "#1D2968")
    val signal = connectionDef(name = "Signal", style = ConnectionLineStyle.DOTTED)
    val power = portDef(name = "PowerPort")
    val mass = attributeDef(name = "Mass")

    val drive = partDef(name = "Drive") {
        port(name = "power", typeId = power.id, direction = PortDirection.In)
    }
    val cell = partDef(name = "Cell") { part(name = "drive", typeId = drive.id) }
    val line = partDef(name = "Line") {
        part(name = "cell", typeId = cell.id)
        port(name = "supply", typeId = power.id, direction = PortDirection.In)
        attribute(name = "weight", typeId = mass.id, default = 12.kg)
        attribute(name = "serial", typeId = mass.id)
    }
    val grid = partDef(name = "Grid") { port(name = "out", typeId = power.id, direction = PortDirection.Out) }
    val plant = partDef(name = "Plant") {
        part(name = "grid", typeId = grid.id)
        part(name = "line", typeId = line.id)
        connect(name = "feed", typeId = feed.id, sourceEndId = "Plant::grid::out", targetEndId = "Plant::line::cell::drive::power")
    }
    ibd(name = "Plant", owner = plant)
}
```
