// erm.kuml.kts — latex-folder fixture: an ERM in Bachman notation. The LaTeX export supports Martin only (Nacharbeit 123),
// so this one is the "ERM family without LaTeX output" case of the folder and workspace export tests.

ermModel("Shop") {
    val customer =
        entity("Customer") {
            id()
        }
    val order =
        entity("Order") {
            id()
            foreignKey(name = "customer_id", references = customer)
        }
    relationship(from = customer, to = order, name = "places")
    diagram(name = "Overview", notation = ErmNotation.BACHMAN)
}
