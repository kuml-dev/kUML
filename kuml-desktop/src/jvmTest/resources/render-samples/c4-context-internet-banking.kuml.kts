c4Model(name = "Internet Banking — Context") {
    val customer = person(name = "Customer") {
        description = "Endnutzer mit Konto"
    }
    val support = person(name = "Support Agent") {
        description = "Interner Mitarbeiter"
    }

    val banking = softwareSystem(name = "Internet Banking") {
        description = "Webanwendung für Endkunden"
    }
    val email = softwareSystem(name = "Email Service") {
        external = true
    }
    val sms = softwareSystem(name = "SMS Gateway") {
        external = true
    }

    relationship(source = customer, target = banking) { technology = "HTTPS" }
    relationship(source = support,  target = banking) { technology = "HTTPS" }
    relationship(source = banking,  target = email)   { technology = "SMTP" }
    relationship(source = banking,  target = sms)     { technology = "HTTPS/JSON" }

    systemContextDiagram(name = "Banking — Context View") {
        include(customer, support, banking, email, sms)
    }
}
