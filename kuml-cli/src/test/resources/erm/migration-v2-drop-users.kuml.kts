// migration-v2-drop-users.kuml.kts — GenerateCommandSqlMigrationTest fixture: a destructive delta
// relative to migration-v1-users.kuml.kts — the "users" entity is gone entirely. Used to exercise
// the --sql-migration refusal path (destructive changes must be refused, not silently emitted).

ermModel("Migration") {
    entity("accounts") {
        id("id", ErmDataType.Integer(64))
    }
    diagram(name = "Overview")
}
