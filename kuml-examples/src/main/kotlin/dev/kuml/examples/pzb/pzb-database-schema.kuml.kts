@file:Suppress("unused")

import dev.kuml.core.dsl.classDiagram
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.extends

/**
 * PZB Datenbank-Schema — Klassendiagramm der Exposed-Tabellen.
 *
 * Reales Modell aus dem PZB-Projekt
 * (`/Users/irakli/IdeaProjects/PDV/PZB/Database/src/main/kotlin/de/pdv/pzb/server/db/v2/tables`).
 * Dient als anspruchsvolles Test-Fixture für den kUML-Renderer: 19 Tabellen,
 * eine abstrakte Basisklasse, Vererbung in alle Klassen, Foreign-Key-Assoziationen
 * mit Multiplicities (`1` für `reference`, `0..1` für `optReference`).
 *
 * Anders als die kompakten Demo-Diagramme stresst dieses Schema den Renderer
 * realistisch: viele Knoten, viele Kanten, mehrere Self-Loops (Self-Reference
 * von `UserMessages.parent` und `UserPosts.parent`), unterschiedliche
 * Attribut-Anzahlen pro Klasse, und mehrere parallele Kanten zwischen
 * denselben Klassen (z.B. `BankTransactions → BankUsers` für sourceUser und
 * destinationUser).
 */
classDiagram(name = "PZB Datenbank-Schema") {

    // V2.x — Opt-in für Edge-Merging: ELK konsolidiert die 18 Generalisierungen
    // zu `AbstractTable` zu einem gemeinsamen Stamm-Segment, statt sie als
    // Fächer in die Ziel-Ecke laufen zu lassen.
    mergeEdges = true

    // ── Abstrakte Basisklasse ─────────────────────────────────────────────────

    val abstractTable =
        classOf(name = "AbstractTable") {
            isAbstract = true
            attribute(name = "id", type = "Long")
            attribute(name = "comment", type = "String?")
        }

    // ── Stamm-Entitäten (keine FKs) ───────────────────────────────────────────

    val largeObjects =
        classOf(name = "LargeObjects") {
            extends(general = abstractTable)
            attribute(name = "updatedAt", type = "Timestamp")
            attribute(name = "data", type = "ByteArray")
            attribute(name = "originalName", type = "String")
        }

    val databaseConfigurations =
        classOf(name = "DatabaseConfigurations") {
            extends(general = abstractTable)
            attribute(name = "key", type = "String")
            attribute(name = "value", type = "String?")
            attribute(name = "intValue", type = "Int?")
            attribute(name = "doubleValue", type = "Double?")
            attribute(name = "moneyValue", type = "Money?")
        }

    // ── Bank-Schicht ──────────────────────────────────────────────────────────

    val bankUsers =
        classOf(name = "BankUsers") {
            extends(general = abstractTable)
            attribute(name = "loginName", type = "String")
            attribute(name = "personalNumber", type = "Long?")
            attribute(name = "firstName", type = "String?")
            attribute(name = "lastName", type = "String?")
            attribute(name = "organisationName", type = "String?")
            attribute(name = "anonymous", type = "Boolean")
            attribute(name = "politician", type = "Boolean")
            attribute(name = "street", type = "String?")
            attribute(name = "city", type = "String?")
            attribute(name = "postCode", type = "String?")
            attribute(name = "stateOrProvince", type = "String?")
            attribute(name = "country", type = "Country")
            attribute(name = "monthlyDonation", type = "Money")
            attribute(name = "currentBalance", type = "Money")
            attribute(name = "salt", type = "ByteArray")
            attribute(name = "passwordHash", type = "ByteArray")
            attribute(name = "passwordResetKey", type = "String")
            attribute(name = "passwordResetKeyValidUntil", type = "Timestamp")
            attribute(name = "iban", type = "String?")
            attribute(name = "bic", type = "String?")
        }

    val bankTransactions =
        classOf(name = "BankTransactions") {
            extends(general = abstractTable)
            attribute(name = "timestamp", type = "Timestamp")
            attribute(name = "subject", type = "String")
            attribute(name = "subjectHtml", type = "String")
            attribute(name = "amount", type = "Money")
        }

    // ── Votes (klassisch) ─────────────────────────────────────────────────────

    val votes =
        classOf(name = "Votes") {
            extends(general = abstractTable)
            attribute(name = "timestamp", type = "Timestamp")
            attribute(name = "weight", type = "Money")
            attribute(name = "subject", type = "String")
            attribute(name = "description", type = "String")
            attribute(name = "descriptionHtml", type = "String")
            attribute(name = "voteState", type = "VoteState")
            attribute(name = "voteParticipationGroup", type = "VoteParticipationGroup")
        }

    val voteOptions =
        classOf(name = "VoteOptions") {
            extends(general = abstractTable)
            attribute(name = "subject", type = "String")
            attribute(name = "neutral", type = "Boolean")
        }

    val voteOffers =
        classOf(name = "VoteOffers") {
            extends(general = abstractTable)
            attribute(name = "relativeAmount", type = "Double")
        }

    // ── Demokratische Abstimmungen ────────────────────────────────────────────

    val democraticVotes =
        classOf(name = "DemocraticVotes") {
            extends(general = abstractTable)
            attribute(name = "timestamp", type = "Timestamp")
            attribute(name = "weight", type = "Money")
            attribute(name = "subject", type = "String")
            attribute(name = "description", type = "String")
            attribute(name = "descriptionHtml", type = "String")
            attribute(name = "voteState", type = "VoteState")
            attribute(name = "voteParticipationGroup", type = "VoteParticipationGroup")
            attribute(name = "secretKey", type = "ByteArray")
        }

    val democraticVoteOptions =
        classOf(name = "DemocraticVoteOptions") {
            extends(general = abstractTable)
            attribute(name = "subject", type = "String")
            attribute(name = "neutral", type = "Boolean")
            attribute(name = "coins", type = "Array<Long>")
        }

    val democraticVotesUsers =
        classOf(name = "DemocraticVotesUsers") {
            extends(general = abstractTable)
        }

    // ── Projekte ──────────────────────────────────────────────────────────────

    val projects =
        classOf(name = "Projects") {
            extends(general = abstractTable)
            attribute(name = "name", type = "String")
            attribute(name = "shortDescription", type = "String")
            attribute(name = "longDescription", type = "String")
            attribute(name = "longDescriptionHtml", type = "String")
            attribute(name = "startTimestamp", type = "Timestamp")
            attribute(name = "endTimestamp", type = "Timestamp?")
            attribute(name = "weight", type = "Money")
        }

    val projectSubscriptions =
        classOf(name = "ProjectSubscriptions") {
            extends(general = abstractTable)
            attribute(name = "projectWeight", type = "Int")
            attribute(name = "startTimestamp", type = "Timestamp")
            attribute(name = "endTimestamp", type = "Timestamp?")
        }

    // ── Posts / Messages / Notifications ──────────────────────────────────────

    val userPosts =
        classOf(name = "UserPosts") {
            extends(general = abstractTable)
            attribute(name = "message", type = "String")
            attribute(name = "messageHtml", type = "String")
            attribute(name = "timestamp", type = "Timestamp")
            attribute(name = "weight", type = "Money")
        }

    val userMessages =
        classOf(name = "UserMessages") {
            extends(general = abstractTable)
            attribute(name = "timestamp", type = "Timestamp")
            attribute(name = "message", type = "String")
            attribute(name = "messageHtml", type = "String")
            attribute(name = "messageState", type = "UserMessageState")
        }

    val notifications =
        classOf(name = "Notifications") {
            extends(general = abstractTable)
            attribute(name = "timestamp", type = "Timestamp")
            attribute(name = "message", type = "String")
            attribute(name = "messageHtml", type = "String")
            attribute(name = "notificationState", type = "NotificationState")
        }

    // ── User-Lifecycle ────────────────────────────────────────────────────────

    val userActivations =
        classOf(name = "UserActivations") {
            extends(general = abstractTable)
            attribute(name = "updatedAt", type = "Timestamp")
            attribute(name = "userType", type = "UserType")
        }

    val emailValidations =
        classOf(name = "EmailValidations") {
            extends(general = abstractTable)
            attribute(name = "email", type = "String")
            attribute(name = "validationKey", type = "String")
            attribute(name = "keyValidUntil", type = "Timestamp")
            attribute(name = "validationState", type = "EmailValidationState")
        }

    val politicianRatings =
        classOf(name = "PoliticianRatings") {
            extends(general = abstractTable)
            attribute(name = "points", type = "Int")
        }

    // ── Foreign-Key-Assoziationen ─────────────────────────────────────────────
    //
    // Multiplicity-Konvention:
    //   reference     → Pflicht-FK     → multiplicity("1")     auf Ziel-Seite
    //   optReference  → optionaler FK  → multiplicity("0..1")  auf Ziel-Seite

    // BankUsers.avatar → LargeObjects
    association(source = bankUsers, target = largeObjects) {
        name = "avatar"
        target { multiplicity(spec = "1") }
    }

    // BankTransactions ↔ BankUsers (zwei parallele Kanten — source/destination)
    association(source = bankTransactions, target = bankUsers) {
        name = "sourceUser"
        target { multiplicity(spec = "1") }
    }
    association(source = bankTransactions, target = bankUsers) {
        name = "destinationUser"
        target { multiplicity(spec = "1") }
    }

    // Votes → LargeObjects (image), BankUsers (user)
    association(source = votes, target = largeObjects) {
        name = "image"
        target { multiplicity(spec = "1") }
    }
    association(source = votes, target = bankUsers) {
        name = "user"
        target { multiplicity(spec = "1") }
    }

    // VoteOptions → Votes
    association(source = voteOptions, target = votes) {
        name = "vote"
        target { multiplicity(spec = "1") }
    }

    // VoteOffers → VoteOptions, BankUsers, BankTransactions (optional)
    association(source = voteOffers, target = voteOptions) {
        name = "voteOption"
        target { multiplicity(spec = "1") }
    }
    association(source = voteOffers, target = bankUsers) {
        name = "bankUser"
        target { multiplicity(spec = "1") }
    }
    association(source = voteOffers, target = bankTransactions) {
        name = "bankTransaction"
        target { multiplicity(spec = "0..1") }
    }

    // DemocraticVotes → LargeObjects, BankUsers
    association(source = democraticVotes, target = largeObjects) {
        name = "image"
        target { multiplicity(spec = "1") }
    }
    association(source = democraticVotes, target = bankUsers) {
        name = "user"
        target { multiplicity(spec = "1") }
    }

    // DemocraticVoteOptions → DemocraticVotes
    association(source = democraticVoteOptions, target = democraticVotes) {
        name = "vote"
        target { multiplicity(spec = "1") }
    }

    // DemocraticVotesUsers — N:M-Brücke
    association(source = democraticVotesUsers, target = democraticVotes) {
        name = "vote"
        target { multiplicity(spec = "1") }
    }
    association(source = democraticVotesUsers, target = bankUsers) {
        name = "user"
        target { multiplicity(spec = "1") }
    }

    // Projects → LargeObjects, BankUsers
    association(source = projects, target = largeObjects) {
        name = "image"
        target { multiplicity(spec = "1") }
    }
    association(source = projects, target = bankUsers) {
        name = "owner"
        target { multiplicity(spec = "1") }
    }

    // ProjectSubscriptions → Projects, BankUsers
    association(source = projectSubscriptions, target = projects) {
        name = "project"
        target { multiplicity(spec = "1") }
    }
    association(source = projectSubscriptions, target = bankUsers) {
        name = "backer"
        target { multiplicity(spec = "1") }
    }

    // UserPosts → LargeObjects?, UserPosts? (parent), BankUsers, Projects?, Votes?, DemocraticVotes?
    association(source = userPosts, target = largeObjects) {
        name = "image"
        target { multiplicity(spec = "0..1") }
    }
    association(source = userPosts, target = userPosts) {
        name = "parent"
        target { multiplicity(spec = "0..1") }
    }
    association(source = userPosts, target = bankUsers) {
        name = "user"
        target { multiplicity(spec = "1") }
    }
    association(source = userPosts, target = projects) {
        name = "project"
        target { multiplicity(spec = "0..1") }
    }
    association(source = userPosts, target = votes) {
        name = "vote"
        target { multiplicity(spec = "0..1") }
    }
    association(source = userPosts, target = democraticVotes) {
        name = "democraticVote"
        target { multiplicity(spec = "0..1") }
    }

    // UserMessages → BankUsers (from/to), UserMessages? (parent)
    association(source = userMessages, target = bankUsers) {
        name = "fromUser"
        target { multiplicity(spec = "1") }
    }
    association(source = userMessages, target = bankUsers) {
        name = "toUser"
        target { multiplicity(spec = "1") }
    }
    association(source = userMessages, target = userMessages) {
        name = "parent"
        target { multiplicity(spec = "0..1") }
    }

    // Notifications → BankUsers
    association(source = notifications, target = bankUsers) {
        name = "user"
        target { multiplicity(spec = "1") }
    }

    // UserActivations → BankUsers
    association(source = userActivations, target = bankUsers) {
        name = "user"
        target { multiplicity(spec = "1") }
    }

    // EmailValidations → BankUsers
    association(source = emailValidations, target = bankUsers) {
        name = "user"
        target { multiplicity(spec = "1") }
    }

    // PoliticianRatings → BankUsers (user + politician — zwei parallele Kanten)
    association(source = politicianRatings, target = bankUsers) {
        name = "user"
        target { multiplicity(spec = "1") }
    }
    association(source = politicianRatings, target = bankUsers) {
        name = "politician"
        target { multiplicity(spec = "1") }
    }
}
