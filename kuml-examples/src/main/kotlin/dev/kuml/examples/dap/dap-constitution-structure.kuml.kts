@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.uml.Visibility
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.constraint
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.returns

/**
 * DAP Verfassung — Strukturmodell (V3.0.6 Chain-Showcase).
 * Single Source of Truth für den on-chain registrierten Modell-Hash.
 */
classDiagram(name = "DAP Verfassung – Strukturmodell") {
    classOf(name = "VerfassungsArtikel") {
        attribute(name = "id", type = "UUID", visibility = Visibility.PRIVATE)
        attribute(name = "titel", type = "String", visibility = Visibility.PUBLIC)
        attribute(name = "text", type = "String", visibility = Visibility.PUBLIC)
        attribute(name = "einreicher", type = "Address", visibility = Visibility.PUBLIC)
        attribute(name = "version", type = "Int", visibility = Visibility.PUBLIC)
        attribute(name = "diskussionsfrist", type = "Duration", visibility = Visibility.PRIVATE)
        attribute(name = "abstimmungsfrist", type = "Duration", visibility = Visibility.PRIVATE)
        attribute(name = "quorum", type = "Percent", visibility = Visibility.PRIVATE)
        attribute(name = "mehrheit", type = "Percent", visibility = Visibility.PRIVATE)
        attribute(name = "karenzfrist", type = "Duration", visibility = Visibility.PRIVATE)
        attribute(name = "minUnterstuetzer", type = "Int", visibility = Visibility.PRIVATE)
        constraint(name = "QuorumImSinnvollenBereich", body = "self.quorum >= 25 and self.quorum <= 100")
        constraint(name = "MindestensEinfacheMehrheit", body = "self.mehrheit > 50")
        constraint(name = "MindestDiskussionsfrist", body = "self.diskussionsfrist >= 7")
        constraint(name = "MindestAbstimmungsfrist", body = "self.abstimmungsfrist >= 3")
    }
    classOf(name = "Abstimmung") {
        attribute(name = "ja", type = "Int")
        attribute(name = "nein", type = "Int")
        attribute(name = "enthaltung", type = "Int")
        attribute(name = "stimmberechtigte", type = "Int")
        operation(name = "beteiligung") { returns(typeName = "Percent") }
        operation(name = "jaAnteil") { returns(typeName = "Percent") }
    }
}
