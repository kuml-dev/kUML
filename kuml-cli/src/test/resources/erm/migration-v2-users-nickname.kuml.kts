// migration-v2-users-nickname.kuml.kts — GenerateCommandSqlMigrationTest fixture: the NEW ("--to")
// snapshot for the additive-only-diff happy path. Adds a nullable "nickname" column to "users"
// relative to migration-v1-users.kuml.kts — nothing else changes.

ermModel("Migration") {
    entity("users") {
        id("id", ErmDataType.Integer(64))
        attribute(name = "email", type = ErmDataType.Varchar(255), nullable = false)
        attribute(name = "nickname", type = ErmDataType.Varchar(255), nullable = true)
    }
    diagram(name = "Overview")
}
