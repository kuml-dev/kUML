package dev.kuml.core.dsl.layout

import dev.kuml.core.dsl.KumlDsl

/**
 * Marker-Interface für DSL-Scopes, die Layout-Hints aufnehmen können.
 *
 * Wird von Element-Buildern implementiert, deren Element in Box-und-Kante-
 * Diagrammen als Knoten auftaucht (UML Class / Interface / Enumeration /
 * Component / Actor / UseCase / State sowie C4 Person / SoftwareSystem /
 * Container / Component).
 *
 * Typische Implementierung in einem Builder:
 * ```kotlin
 * class ClassBuilder … : UmlClassifierScope, LayoutHintsScope {
 *     override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()
 *
 *     internal fun buildClass(): UmlClass = UmlClass(
 *         …,
 *         metadata = layoutHintsBuilder.toMetadata(),
 *     )
 * }
 * ```
 *
 * Der [layout]-Block steht dann im Builder-Lambda zur Verfügung:
 * ```kotlin
 * classOf("Order") {
 *     layout {
 *         col = 2
 *         row = 1
 *         rightOf("Customer")
 *     }
 * }
 * ```
 *
 * Alle erzeugten Schlüssel befinden sich im reservierten Namespace
 * `kuml.layout.*` (siehe [LayoutMetadataKeys.NAMESPACE]).
 */
@KumlDsl
public interface LayoutHintsScope {
    /** Sammler für die Layout-Hints dieses Elements. */
    public val layoutHintsBuilder: LayoutHintsBuilder
}

/**
 * Öffnet einen `layout { … }`-Block im Scope des aktuellen Element-Builders.
 *
 * Alle gesetzten Hints werden beim `build()`-Aufruf des Elements unter dem
 * Namespace [LayoutMetadataKeys.NAMESPACE] in das `metadata`-Feld materialisiert.
 *
 * Nur Felder, die vom Default-Wert abweichen, erzeugen Metadaten-Einträge.
 * Ohne `layout {}`-Block bleibt `metadata` leer.
 *
 * ```kotlin
 * classOf("Order") {
 *     layout {
 *         col = 2
 *         row = 1
 *         colSpan = 2
 *         pinned = true
 *         rightOf("Customer")
 *     }
 * }
 * ```
 *
 * @param block Konfigurationsblock für die [LayoutHintsBuilder]-Properties.
 */
public fun LayoutHintsScope.layout(block: LayoutHintsBuilder.() -> Unit) {
    layoutHintsBuilder.block()
}
