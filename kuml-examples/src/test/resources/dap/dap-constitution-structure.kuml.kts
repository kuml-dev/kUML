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
classDiagram("DAP Verfassung – Strukturmodell") {
    classOf("VerfassungsArtikel") {
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
        constraint("QuorumImSinnvollenBereich", "self.quorum >= 25 and self.quorum <= 100")
        constraint("MindestensEinfacheMehrheit", "self.mehrheit > 50")
        constraint("MindestDiskussionsfrist", "self.diskussionsfrist >= 7")
        constraint("MindestAbstimmungsfrist", "self.abstimmungsfrist >= 3")
    }
    classOf("Abstimmung") {
        attribute(name = "ja", type = "Int")
        attribute(name = "nein", type = "Int")
        attribute(name = "enthaltung", type = "Int")
        attribute(name = "stimmberechtigte", type = "Int")
        operation("beteiligung") { returns("Percent") }
        operation("jaAnteil") { returns("Percent") }
    }
}
