// erm.kuml.kts — latex-folder fixture: an ERM in Chen notation. The LaTeX export supports Martin, Bachman and IDEF1X (Nacharbeit 126),
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
    diagram(name = "Overview", notation = ErmNotation.CHEN)
}
