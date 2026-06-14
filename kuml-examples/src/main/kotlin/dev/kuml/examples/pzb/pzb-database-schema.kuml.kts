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
classDiagram("PZB Datenbank-Schema") {

    // V2.x — Opt-in für Edge-Merging: ELK konsolidiert die 18 Generalisierungen
    // zu `AbstractTable` zu einem gemeinsamen Stamm-Segment, statt sie als
    // Fächer in die Ziel-Ecke laufen zu lassen.
    mergeEdges = true

    // ── Abstrakte Basisklasse ─────────────────────────────────────────────────

    val abstractTable =
        classOf("AbstractTable") {
            isAbstract = true
            attribute("id", type = "Long")
            attribute("comment", type = "String?")
        }

    // ── Stamm-Entitäten (keine FKs) ───────────────────────────────────────────

    val largeObjects =
        classOf("LargeObjects") {
            extends(abstractTable)
            attribute("updatedAt", type = "Timestamp")
            attribute("data", type = "ByteArray")
            attribute("originalName", type = "String")
        }

    val databaseConfigurations =
        classOf("DatabaseConfigurations") {
            extends(abstractTable)
            attribute("key", type = "String")
            attribute("value", type = "String?")
            attribute("intValue", type = "Int?")
            attribute("doubleValue", type = "Double?")
            attribute("moneyValue", type = "Money?")
        }

    // ── Bank-Schicht ──────────────────────────────────────────────────────────

    val bankUsers =
        classOf("BankUsers") {
            extends(abstractTable)
            attribute("loginName", type = "String")
            attribute("personalNumber", type = "Long?")
            attribute("firstName", type = "String?")
            attribute("lastName", type = "String?")
            attribute("organisationName", type = "String?")
            attribute("anonymous", type = "Boolean")
            attribute("politician", type = "Boolean")
            attribute("street", type = "String?")
            attribute("city", type = "String?")
            attribute("postCode", type = "String?")
            attribute("stateOrProvince", type = "String?")
            attribute("country", type = "Country")
            attribute("monthlyDonation", type = "Money")
            attribute("currentBalance", type = "Money")
            attribute("salt", type = "ByteArray")
            attribute("passwordHash", type = "ByteArray")
            attribute("passwordResetKey", type = "String")
            attribute("passwordResetKeyValidUntil", type = "Timestamp")
            attribute("iban", type = "String?")
            attribute("bic", type = "String?")
        }

    val bankTransactions =
        classOf("BankTransactions") {
            extends(abstractTable)
            attribute("timestamp", type = "Timestamp")
            attribute("subject", type = "String")
            attribute("subjectHtml", type = "String")
            attribute("amount", type = "Money")
        }

    // ── Votes (klassisch) ─────────────────────────────────────────────────────

    val votes =
        classOf("Votes") {
            extends(abstractTable)
            attribute("timestamp", type = "Timestamp")
            attribute("weight", type = "Money")
            attribute("subject", type = "String")
            attribute("description", type = "String")
            attribute("descriptionHtml", type = "String")
            attribute("voteState", type = "VoteState")
            attribute("voteParticipationGroup", type = "VoteParticipationGroup")
        }

    val voteOptions =
        classOf("VoteOptions") {
            extends(abstractTable)
            attribute("subject", type = "String")
            attribute("neutral", type = "Boolean")
        }

    val voteOffers =
        classOf("VoteOffers") {
            extends(abstractTable)
            attribute("relativeAmount", type = "Double")
        }

    // ── Demokratische Abstimmungen ────────────────────────────────────────────

    val democraticVotes =
        classOf("DemocraticVotes") {
            extends(abstractTable)
            attribute("timestamp", type = "Timestamp")
            attribute("weight", type = "Money")
            attribute("subject", type = "String")
            attribute("description", type = "String")
            attribute("descriptionHtml", type = "String")
            attribute("voteState", type = "VoteState")
            attribute("voteParticipationGroup", type = "VoteParticipationGroup")
            attribute("secretKey", type = "ByteArray")
        }

    val democraticVoteOptions =
        classOf("DemocraticVoteOptions") {
            extends(abstractTable)
            attribute("subject", type = "String")
            attribute("neutral", type = "Boolean")
            attribute("coins", type = "Array<Long>")
        }

    val democraticVotesUsers =
        classOf("DemocraticVotesUsers") {
            extends(abstractTable)
        }

    // ── Projekte ──────────────────────────────────────────────────────────────

    val projects =
        classOf("Projects") {
            extends(abstractTable)
            attribute("name", type = "String")
            attribute("shortDescription", type = "String")
            attribute("longDescription", type = "String")
            attribute("longDescriptionHtml", type = "String")
            attribute("startTimestamp", type = "Timestamp")
            attribute("endTimestamp", type = "Timestamp?")
            attribute("weight", type = "Money")
        }

    val projectSubscriptions =
        classOf("ProjectSubscriptions") {
            extends(abstractTable)
            attribute("projectWeight", type = "Int")
            attribute("startTimestamp", type = "Timestamp")
            attribute("endTimestamp", type = "Timestamp?")
        }

    // ── Posts / Messages / Notifications ──────────────────────────────────────

    val userPosts =
        classOf("UserPosts") {
            extends(abstractTable)
            attribute("message", type = "String")
            attribute("messageHtml", type = "String")
            attribute("timestamp", type = "Timestamp")
            attribute("weight", type = "Money")
        }

    val userMessages =
        classOf("UserMessages") {
            extends(abstractTable)
            attribute("timestamp", type = "Timestamp")
            attribute("message", type = "String")
            attribute("messageHtml", type = "String")
            attribute("messageState", type = "UserMessageState")
        }

    val notifications =
        classOf("Notifications") {
            extends(abstractTable)
            attribute("timestamp", type = "Timestamp")
            attribute("message", type = "String")
            attribute("messageHtml", type = "String")
            attribute("notificationState", type = "NotificationState")
        }

    // ── User-Lifecycle ────────────────────────────────────────────────────────

    val userActivations =
        classOf("UserActivations") {
            extends(abstractTable)
            attribute("updatedAt", type = "Timestamp")
            attribute("userType", type = "UserType")
        }

    val emailValidations =
        classOf("EmailValidations") {
            extends(abstractTable)
            attribute("email", type = "String")
            attribute("validationKey", type = "String")
            attribute("keyValidUntil", type = "Timestamp")
            attribute("validationState", type = "EmailValidationState")
        }

    val politicianRatings =
        classOf("PoliticianRatings") {
            extends(abstractTable)
            attribute("points", type = "Int")
        }

    // ── Foreign-Key-Assoziationen ─────────────────────────────────────────────
    //
    // Multiplicity-Konvention:
    //   reference     → Pflicht-FK     → multiplicity("1")     auf Ziel-Seite
    //   optReference  → optionaler FK  → multiplicity("0..1")  auf Ziel-Seite

    // BankUsers.avatar → LargeObjects
    association(source = bankUsers, target = largeObjects) {
        name = "avatar"
        target { multiplicity("1") }
    }

    // BankTransactions ↔ BankUsers (zwei parallele Kanten — source/destination)
    association(source = bankTransactions, target = bankUsers) {
        name = "sourceUser"
        target { multiplicity("1") }
    }
    association(source = bankTransactions, target = bankUsers) {
        name = "destinationUser"
        target { multiplicity("1") }
    }

    // Votes → LargeObjects (image), BankUsers (user)
    association(source = votes, target = largeObjects) {
        name = "image"
        target { multiplicity("1") }
    }
    association(source = votes, target = bankUsers) {
        name = "user"
        target { multiplicity("1") }
    }

    // VoteOptions → Votes
    association(source = voteOptions, target = votes) {
        name = "vote"
        target { multiplicity("1") }
    }

    // VoteOffers → VoteOptions, BankUsers, BankTransactions (optional)
    association(source = voteOffers, target = voteOptions) {
        name = "voteOption"
        target { multiplicity("1") }
    }
    association(source = voteOffers, target = bankUsers) {
        name = "bankUser"
        target { multiplicity("1") }
    }
    association(source = voteOffers, target = bankTransactions) {
        name = "bankTransaction"
        target { multiplicity("0..1") }
    }

    // DemocraticVotes → LargeObjects, BankUsers
    association(source = democraticVotes, target = largeObjects) {
        name = "image"
        target { multiplicity("1") }
    }
    association(source = democraticVotes, target = bankUsers) {
        name = "user"
        target { multiplicity("1") }
    }

    // DemocraticVoteOptions → DemocraticVotes
    association(source = democraticVoteOptions, target = democraticVotes) {
        name = "vote"
        target { multiplicity("1") }
    }

    // DemocraticVotesUsers — N:M-Brücke
    association(source = democraticVotesUsers, target = democraticVotes) {
        name = "vote"
        target { multiplicity("1") }
    }
    association(source = democraticVotesUsers, target = bankUsers) {
        name = "user"
        target { multiplicity("1") }
    }

    // Projects → LargeObjects, BankUsers
    association(source = projects, target = largeObjects) {
        name = "image"
        target { multiplicity("1") }
    }
    association(source = projects, target = bankUsers) {
        name = "owner"
        target { multiplicity("1") }
    }

    // ProjectSubscriptions → Projects, BankUsers
    association(source = projectSubscriptions, target = projects) {
        name = "project"
        target { multiplicity("1") }
    }
    association(source = projectSubscriptions, target = bankUsers) {
        name = "backer"
        target { multiplicity("1") }
    }

    // UserPosts → LargeObjects?, UserPosts? (parent), BankUsers, Projects?, Votes?, DemocraticVotes?
    association(source = userPosts, target = largeObjects) {
        name = "image"
        target { multiplicity("0..1") }
    }
    association(source = userPosts, target = userPosts) {
        name = "parent"
        target { multiplicity("0..1") }
    }
    association(source = userPosts, target = bankUsers) {
        name = "user"
        target { multiplicity("1") }
    }
    association(source = userPosts, target = projects) {
        name = "project"
        target { multiplicity("0..1") }
    }
    association(source = userPosts, target = votes) {
        name = "vote"
        target { multiplicity("0..1") }
    }
    association(source = userPosts, target = democraticVotes) {
        name = "democraticVote"
        target { multiplicity("0..1") }
    }

    // UserMessages → BankUsers (from/to), UserMessages? (parent)
    association(source = userMessages, target = bankUsers) {
        name = "fromUser"
        target { multiplicity("1") }
    }
    association(source = userMessages, target = bankUsers) {
        name = "toUser"
        target { multiplicity("1") }
    }
    association(source = userMessages, target = userMessages) {
        name = "parent"
        target { multiplicity("0..1") }
    }

    // Notifications → BankUsers
    association(source = notifications, target = bankUsers) {
        name = "user"
        target { multiplicity("1") }
    }

    // UserActivations → BankUsers
    association(source = userActivations, target = bankUsers) {
        name = "user"
        target { multiplicity("1") }
    }

    // EmailValidations → BankUsers
    association(source = emailValidations, target = bankUsers) {
        name = "user"
        target { multiplicity("1") }
    }

    // PoliticianRatings → BankUsers (user + politician — zwei parallele Kanten)
    association(source = politicianRatings, target = bankUsers) {
        name = "user"
        target { multiplicity("1") }
    }
    association(source = politicianRatings, target = bankUsers) {
        name = "politician"
        target { multiplicity("1") }
    }
}
