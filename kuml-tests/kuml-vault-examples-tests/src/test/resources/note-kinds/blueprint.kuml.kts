import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.Sentiment

blueprint(name = "Service") {
    val staff = actor(name = "Staff", role = ActorRole.STAFF)
    lateinit var fill: String
    lateinit var check: String
    phase(name = "Request") {
        fill = customer(name = "Fills in the form", sentiment = Sentiment.NEUTRAL)
        check = backstage(name = "Checks the data", actor = staff)
    }
    phase(name = "Admission") {
        customer(name = "Waits", sentiment = Sentiment.NEGATIVE)
    }
    note("A plain note without a symbol.", fill, kind = NoteKind.NOTE)
    note("Warning: totals are not rounded here.", fill, kind = NoteKind.WARNING)
    note("Error: the id must never be null.", check, kind = NoteKind.ERROR)
    note("Decision: forms are immutable once sent, <b>always</b> & forever.", fill, check, kind = NoteKind.DECISION)
    note("Todo: add the discount rules.", check, kind = NoteKind.TODO)
    note("Free-standing note", id = "free", kind = NoteKind.QUESTION)
    blueprintDiagram(name = "Blueprint", emotionCurve = false)
}
