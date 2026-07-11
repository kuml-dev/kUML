// migration-v1-users.kuml.kts — GenerateCommandSqlMigrationTest fixture: the OLD ("--from") snapshot.
// Paired with migration-v2-users-nickname.kuml.kts for an additive-only diff, and with
// migration-v2-drop-users.kuml.kts for a destructive (refused) diff.

ermModel("Migration") {
    entity("users") {
        id("id", ErmDataType.Integer(64))
        attribute(name = "email", type = ErmDataType.Varchar(255), nullable = false)
    }
    diagram(name = "Overview")
}
