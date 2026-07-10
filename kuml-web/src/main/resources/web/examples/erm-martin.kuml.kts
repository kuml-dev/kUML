// erm-martin.kuml.kts — kuml-web playground example: a small ERM (Crow's-Foot
// / Martin notation) schema for a simple blog. Exercises entities, a
// foreign-key relationship, and the default MARTIN notation.

ermModel("Blog Schema") {
    val author =
        entity("Author") {
            id()
            attribute(name = "name", type = ErmDataType.Varchar(120), nullable = false)
            attribute(name = "email", type = ErmDataType.Varchar(255), unique = true)
        }
    val post =
        entity("Post") {
            id()
            foreignKey(name = "author_id", references = author, nullable = false)
            attribute(name = "title", type = ErmDataType.Varchar(255), nullable = false)
            attribute(name = "body", type = ErmDataType.Text)
            attribute(name = "published_at", type = ErmDataType.Timestamp())
        }

    relationship(from = author, to = post, name = "writes")

    diagram(name = "Overview", notation = ErmNotation.MARTIN)
}
