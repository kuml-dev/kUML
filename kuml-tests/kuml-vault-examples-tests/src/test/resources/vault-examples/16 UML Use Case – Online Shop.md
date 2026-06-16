---
title: UML Use-Case-Diagramm – Online Shop
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - use-case
status: aktiv
---

# UML Use-Case-Diagramm — Online Shop

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Use-Case-Diagramm** zeigt die Außensicht eines Systems: wer (Akteure) tut was (Use-Cases). Hier: Ein Kunde nutzt einen Online-Shop für `Place Order`, der `Validate Cart` *immer* einbindet und um `Apply Discount` *bei Bedarf* erweitert wird. Ein zweiter Akteur (`Payment Gateway`) ist ein externes System.

## Diagramm

```kuml
useCaseDiagram(name = "Online Shop") {
    val customer = actor(name = "Customer")
    val payment  = actor(name = "Payment Gateway")

    val placeOrder    = useCase(name = "Place Order")
    val validateCart  = useCase(name = "Validate Cart")
    val applyDiscount = useCase(name = "Apply Discount")
    val charge        = useCase(name = "Charge Card")

    subject(name = "Online Shop", containedUseCases =
        arrayOf(placeOrder, validateCart, applyDiscount, charge))

    association(source = customer, target = placeOrder)
    association(source = charge, target = payment)

    include(base = placeOrder, addition = validateCart)
    extend(base = placeOrder, extension = applyDiscount, at = "PaymentChosen")
    include(base = placeOrder, addition = charge)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `useCaseDiagram(name = …) { … }` | Top-Level: erzeugt ein Use-Case-Diagramm. |
| `actor(name = …)` | Externer Akteur (Mensch oder Fremdsystem). |
| `useCase(name = …)` | Ein Use-Case (Anwendungsfall). |
| `subject(name = …, containedUseCases = arrayOf(…))` | Systemgrenze, die mehrere Use-Cases umschließt. |
| `association(source = …, target = …)` | Verbindung Akteur ↔ Use-Case. |
| `include(base = …, addition = …)` | `«include»` — der `base`-Use-Case nutzt **immer** den `addition`-Use-Case. |
| `extend(base = …, extension = …, at = "ExtPunkt")` | `«extend»` — der `extension`-Use-Case erweitert `base` **optional** an `at`. |

## Mögliche Erweiterungen

- **Spezialisierte Akteure**: `generalization(child = customer, parent = guestUser)`
- **Use-Case-Beschreibung**: über `useCase(name = …, description = "…")`
- **Mehrere Subjects**: ein zweites `subject` für ein Subsystem

## Verwandte Beispiele

- [[05 SysML 2 UC – Library System]] — SysML-2-Pendant
- [[17 UML Activity – Checkout Flow]] — der Use-Case `Place Order` als Aktivität
- [[19 UML Sequence – API Submit]] — derselbe Use-Case als Sequenzdiagramm
