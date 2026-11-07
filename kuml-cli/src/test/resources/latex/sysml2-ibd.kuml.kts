import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "HybridVehicleSystem") {
    // Connection-Typen für die Verdrahtung
    val powerLine  = connectionDef(name = "PowerLine")
    val driveshaft = connectionDef(name = "Driveshaft")

    // Port-Typen — jeder port(...) braucht eine typeId-Referenz
    val dcPort    = portDef(name = "DcPort")
    val shaftPort = portDef(name = "ShaftPort")

    val battery = partDef(name = "Battery") {
        port(name = "dcOut", typeId = dcPort.id)
    }
    val electricMotor = partDef(name = "ElectricMotor") {
        port(name = "dcIn",  typeId = dcPort.id)
        port(name = "shaft", typeId = shaftPort.id)
    }
    val iceEngine = partDef(name = "InternalCombustionEngine") {
        port(name = "shaft", typeId = shaftPort.id)
    }
    val powerSplitter = partDef(name = "PowerSplitter") {
        port(name = "emIn",  typeId = shaftPort.id)
        port(name = "iceIn", typeId = shaftPort.id)
    }

    val hybrid = partDef(name = "HybridVehicle") {
        part(name = "battery",       typeId = battery.id)
        part(name = "electricMotor", typeId = electricMotor.id)
        part(name = "iceEngine",     typeId = iceEngine.id)
        part(name = "powerSplitter", typeId = powerSplitter.id)

        connect(
            name = "batteryToMotor",
            typeId = powerLine.id,
            sourceEndId = "HybridVehicle::battery::dcOut",
            targetEndId = "HybridVehicle::electricMotor::dcIn",
        )
        connect(
            name = "motorToSplitter",
            typeId = driveshaft.id,
            sourceEndId = "HybridVehicle::electricMotor::shaft",
            targetEndId = "HybridVehicle::powerSplitter::emIn",
        )
        connect(
            name = "iceToSplitter",
            typeId = driveshaft.id,
            sourceEndId = "HybridVehicle::iceEngine::shaft",
            targetEndId = "HybridVehicle::powerSplitter::iceIn",
        )
    }

    ibd(name = "HybridVehicle — internal block diagram", owner = hybrid)
}
