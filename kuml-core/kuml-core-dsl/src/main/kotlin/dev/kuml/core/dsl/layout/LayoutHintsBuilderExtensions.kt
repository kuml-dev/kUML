package dev.kuml.core.dsl.layout

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlUseCase

// ─────────────────────────────────────────────────────────────────────────────
// Convenience-Overloads für bekannte UML-/C4-Modell-Typen
//
// Da ADR-0001 (Pure Kotlin Model) untersagt, die Metamodell-Datenklassen zu
// verändern, implementieren UmlClass, C4Container & Co. HasId nicht direkt.
// Diese Extensions ermöglichen dennoch typsichere Builder-Handle-Overloads:
//
//   val customer = classOf("Customer") { … }
//   classOf("Order") { layout { rightOf(customer) } }
//
// Jede Funktion extrahiert einfach `.id` und ruft den String-Overload auf.
// ─────────────────────────────────────────────────────────────────────────────

// ── UML ───────────────────────────────────────────────────────────────────────

/** Setzt diesen Knoten oberhalb von [other] ([UmlClass]). */
public fun LayoutHintsBuilder.above(other: UmlClass): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([UmlClass]). */
public fun LayoutHintsBuilder.below(other: UmlClass): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([UmlClass]). */
public fun LayoutHintsBuilder.leftOf(other: UmlClass): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([UmlClass]). */
public fun LayoutHintsBuilder.rightOf(other: UmlClass): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([UmlClass]). */
public fun LayoutHintsBuilder.sameRowAs(other: UmlClass): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([UmlClass]). */
public fun LayoutHintsBuilder.sameColAs(other: UmlClass): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([UmlInterface]). */
public fun LayoutHintsBuilder.above(other: UmlInterface): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([UmlInterface]). */
public fun LayoutHintsBuilder.below(other: UmlInterface): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([UmlInterface]). */
public fun LayoutHintsBuilder.leftOf(other: UmlInterface): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([UmlInterface]). */
public fun LayoutHintsBuilder.rightOf(other: UmlInterface): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([UmlInterface]). */
public fun LayoutHintsBuilder.sameRowAs(other: UmlInterface): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([UmlInterface]). */
public fun LayoutHintsBuilder.sameColAs(other: UmlInterface): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([UmlEnumeration]). */
public fun LayoutHintsBuilder.above(other: UmlEnumeration): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([UmlEnumeration]). */
public fun LayoutHintsBuilder.below(other: UmlEnumeration): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([UmlEnumeration]). */
public fun LayoutHintsBuilder.leftOf(other: UmlEnumeration): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([UmlEnumeration]). */
public fun LayoutHintsBuilder.rightOf(other: UmlEnumeration): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([UmlEnumeration]). */
public fun LayoutHintsBuilder.sameRowAs(other: UmlEnumeration): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([UmlEnumeration]). */
public fun LayoutHintsBuilder.sameColAs(other: UmlEnumeration): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([UmlComponent]). */
public fun LayoutHintsBuilder.above(other: UmlComponent): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([UmlComponent]). */
public fun LayoutHintsBuilder.below(other: UmlComponent): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([UmlComponent]). */
public fun LayoutHintsBuilder.leftOf(other: UmlComponent): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([UmlComponent]). */
public fun LayoutHintsBuilder.rightOf(other: UmlComponent): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([UmlComponent]). */
public fun LayoutHintsBuilder.sameRowAs(other: UmlComponent): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([UmlComponent]). */
public fun LayoutHintsBuilder.sameColAs(other: UmlComponent): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([UmlActor]). */
public fun LayoutHintsBuilder.above(other: UmlActor): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([UmlActor]). */
public fun LayoutHintsBuilder.below(other: UmlActor): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([UmlActor]). */
public fun LayoutHintsBuilder.leftOf(other: UmlActor): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([UmlActor]). */
public fun LayoutHintsBuilder.rightOf(other: UmlActor): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([UmlActor]). */
public fun LayoutHintsBuilder.sameRowAs(other: UmlActor): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([UmlActor]). */
public fun LayoutHintsBuilder.sameColAs(other: UmlActor): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([UmlUseCase]). */
public fun LayoutHintsBuilder.above(other: UmlUseCase): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([UmlUseCase]). */
public fun LayoutHintsBuilder.below(other: UmlUseCase): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([UmlUseCase]). */
public fun LayoutHintsBuilder.leftOf(other: UmlUseCase): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([UmlUseCase]). */
public fun LayoutHintsBuilder.rightOf(other: UmlUseCase): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([UmlUseCase]). */
public fun LayoutHintsBuilder.sameRowAs(other: UmlUseCase): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([UmlUseCase]). */
public fun LayoutHintsBuilder.sameColAs(other: UmlUseCase): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([UmlState]). */
public fun LayoutHintsBuilder.above(other: UmlState): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([UmlState]). */
public fun LayoutHintsBuilder.below(other: UmlState): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([UmlState]). */
public fun LayoutHintsBuilder.leftOf(other: UmlState): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([UmlState]). */
public fun LayoutHintsBuilder.rightOf(other: UmlState): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([UmlState]). */
public fun LayoutHintsBuilder.sameRowAs(other: UmlState): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([UmlState]). */
public fun LayoutHintsBuilder.sameColAs(other: UmlState): Unit = sameColAs(other.id)

// ── C4 ────────────────────────────────────────────────────────────────────────

/** Setzt diesen Knoten oberhalb von [other] ([C4Person]). */
public fun LayoutHintsBuilder.above(other: C4Person): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([C4Person]). */
public fun LayoutHintsBuilder.below(other: C4Person): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([C4Person]). */
public fun LayoutHintsBuilder.leftOf(other: C4Person): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([C4Person]). */
public fun LayoutHintsBuilder.rightOf(other: C4Person): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([C4Person]). */
public fun LayoutHintsBuilder.sameRowAs(other: C4Person): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([C4Person]). */
public fun LayoutHintsBuilder.sameColAs(other: C4Person): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([C4SoftwareSystem]). */
public fun LayoutHintsBuilder.above(other: C4SoftwareSystem): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([C4SoftwareSystem]). */
public fun LayoutHintsBuilder.below(other: C4SoftwareSystem): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([C4SoftwareSystem]). */
public fun LayoutHintsBuilder.leftOf(other: C4SoftwareSystem): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([C4SoftwareSystem]). */
public fun LayoutHintsBuilder.rightOf(other: C4SoftwareSystem): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([C4SoftwareSystem]). */
public fun LayoutHintsBuilder.sameRowAs(other: C4SoftwareSystem): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([C4SoftwareSystem]). */
public fun LayoutHintsBuilder.sameColAs(other: C4SoftwareSystem): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([C4Container]). */
public fun LayoutHintsBuilder.above(other: C4Container): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([C4Container]). */
public fun LayoutHintsBuilder.below(other: C4Container): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([C4Container]). */
public fun LayoutHintsBuilder.leftOf(other: C4Container): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([C4Container]). */
public fun LayoutHintsBuilder.rightOf(other: C4Container): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([C4Container]). */
public fun LayoutHintsBuilder.sameRowAs(other: C4Container): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([C4Container]). */
public fun LayoutHintsBuilder.sameColAs(other: C4Container): Unit = sameColAs(other.id)

/** Setzt diesen Knoten oberhalb von [other] ([C4Component]). */
public fun LayoutHintsBuilder.above(other: C4Component): Unit = above(other.id)

/** Setzt diesen Knoten unterhalb von [other] ([C4Component]). */
public fun LayoutHintsBuilder.below(other: C4Component): Unit = below(other.id)

/** Setzt diesen Knoten links von [other] ([C4Component]). */
public fun LayoutHintsBuilder.leftOf(other: C4Component): Unit = leftOf(other.id)

/** Setzt diesen Knoten rechts von [other] ([C4Component]). */
public fun LayoutHintsBuilder.rightOf(other: C4Component): Unit = rightOf(other.id)

/** Setzt diesen Knoten in dieselbe Zeile wie [other] ([C4Component]). */
public fun LayoutHintsBuilder.sameRowAs(other: C4Component): Unit = sameRowAs(other.id)

/** Setzt diesen Knoten in dieselbe Spalte wie [other] ([C4Component]). */
public fun LayoutHintsBuilder.sameColAs(other: C4Component): Unit = sameColAs(other.id)
