// broken-fk.kuml.kts — V3.4.1 CLI smoke fixture: FK targets a non-existent entity.
// Deliberately broken to exercise `kuml validate`'s ERM violation reporting.

ermModel("Broken") {
    entity("Order") {
        id()
        foreignKey(name = "customer_id", references = "entity-does-not-exist")
    }
    diagram(name = "Overview")
}
