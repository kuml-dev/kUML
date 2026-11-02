package dev.kuml.blueprint.model

import kotlinx.serialization.Serializable

/**
 * Display texts of a Service-Blueprint diagram: the swimlane headers, the three
 * Shostack line captions and the tooltip prefixes.
 *
 * The default is English ([ENGLISH]); a diagram opts into another language
 * explicitly, e.g. `blueprintDiagram(name = "...", labels = BlueprintLabels.GERMAN)`.
 * Nothing here reads the JVM locale or any system property, so the output of a
 * renderer depends only on the model (deterministic, also on Kotlin/Wasm).
 *
 * PAL wave 2G-c.
 */
@Serializable
data class BlueprintLabels(
    val customerActions: String = "Customer Actions",
    val frontstage: String = "Frontstage",
    val backstage: String = "Backstage",
    val supportProcesses: String = "Support Processes",
    val lineOfInteraction: String = "Line of Interaction",
    val lineOfVisibility: String = "Line of Visibility",
    val lineOfInternalInteraction: String = "Line of Internal Interaction",
    /** Prefix of the pain-point tooltip. */
    val painPrefix: String = "Pain: ",
    /** Prefix of the opportunity tooltip. */
    val opportunityPrefix: String = "Chance: ",
) {
    /** Swimlane header of [l]. */
    fun layer(l: BlueprintLayer): String =
        when (l) {
            BlueprintLayer.CUSTOMER_ACTIONS -> customerActions
            BlueprintLayer.FRONTSTAGE -> frontstage
            BlueprintLayer.BACKSTAGE -> backstage
            BlueprintLayer.SUPPORT_PROCESSES -> supportProcesses
        }

    /** Caption of the separator line [l]. */
    fun line(l: BlueprintLine): String =
        when (l) {
            BlueprintLine.INTERACTION -> lineOfInteraction
            BlueprintLine.VISIBILITY -> lineOfVisibility
            BlueprintLine.INTERNAL_INTERACTION -> lineOfInternalInteraction
        }

    companion object {
        /** The default: English texts. */
        val ENGLISH: BlueprintLabels = BlueprintLabels()

        /** German texts. */
        val GERMAN: BlueprintLabels =
            BlueprintLabels(
                customerActions = "Kundenaktionen",
                frontstage = "Frontstage",
                backstage = "Backstage",
                supportProcesses = "Unterstützende Prozesse",
                lineOfInteraction = "Interaktionslinie",
                lineOfVisibility = "Sichtbarkeitslinie",
                lineOfInternalInteraction = "Linie der internen Interaktion",
                painPrefix = "Schmerzpunkt: ",
                opportunityPrefix = "Chance: ",
            )
    }
}
