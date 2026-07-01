package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.EventDefinition

/**
 * SVG-Pfad-Konstanten für alle BPMN-EventDefinition-Symbole.
 *
 * Jedes Symbol ist ein rohes SVG-Fragment (ohne umgebende `<g>`-Tags),
 * normalisiert auf ein 24×24-Koordinatensystem. Zur Darstellung wird es
 * via `transform="translate/scale"` in den Zielbereich skaliert.
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 */
internal object BpmnEventSymbols {
    /** MESSAGE (catching): Umschlag-Outline */
    val MESSAGE =
        """<rect x="2" y="6" width="20" height="12" rx="1" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
            """<polyline points="2,6 12,14 22,6" fill="none" stroke="currentColor" stroke-width="1.5"/>"""

    /** MESSAGE_FILLED (throwing): Umschlag gefüllt */
    val MESSAGE_FILLED =
        """<rect x="2" y="6" width="20" height="12" rx="1" fill="currentColor"/>""" +
            """<polyline points="2,6 12,13 22,6" fill="none" stroke="white" stroke-width="1.5"/>"""

    /** TIMER: Uhr */
    val TIMER =
        """<circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
            """<line x1="12" y1="6" x2="12" y2="12" stroke="currentColor" stroke-width="1.5"/>""" +
            """<line x1="12" y1="12" x2="16" y2="12" stroke="currentColor" stroke-width="1.5"/>"""

    /** ERROR: Blitz */
    val ERROR =
        """<polyline points="14,3 8,13 13,13 10,21 16,11 11,11" fill="currentColor" stroke="currentColor" stroke-width="0.5"/>"""

    /** ESCALATION (catching): Pfeil nach oben (Outline) */
    val ESCALATION_OUTLINE = """<polygon points="12,4 18,18 12,14 6,18" fill="none" stroke="currentColor" stroke-width="1.5"/>"""

    /** ESCALATION (throwing): Pfeil nach oben (gefüllt) */
    val ESCALATION = """<polygon points="12,4 18,18 12,14 6,18" fill="currentColor"/>"""

    /** SIGNAL (catching): Dreieck */
    val SIGNAL = """<polygon points="12,4 20,20 4,20" fill="none" stroke="currentColor" stroke-width="1.5"/>"""

    /** SIGNAL_FILLED (throwing): Dreieck gefüllt */
    val SIGNAL_FILLED = """<polygon points="12,4 20,20 4,20" fill="currentColor"/>"""

    /** COMPENSATION (catching): Doppel-Pfeil links (Outline) */
    val COMPENSATION_OUTLINE =
        """<polygon points="12,6 4,12 12,18" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
            """<polygon points="20,6 12,12 20,18" fill="none" stroke="currentColor" stroke-width="1.5"/>"""

    /** COMPENSATION (throwing): Doppel-Pfeil links (gefüllt) */
    val COMPENSATION =
        """<polygon points="12,6 4,12 12,18" fill="currentColor"/>""" +
            """<polygon points="20,6 12,12 20,18" fill="currentColor"/>"""

    /** CONDITIONAL: Dokument mit Linien */
    val CONDITIONAL =
        """<rect x="5" y="3" width="14" height="18" rx="1" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
            """<line x1="8" y1="8" x2="16" y2="8" stroke="currentColor" stroke-width="1"/>""" +
            """<line x1="8" y1="12" x2="16" y2="12" stroke="currentColor" stroke-width="1"/>""" +
            """<line x1="8" y1="16" x2="13" y2="16" stroke="currentColor" stroke-width="1"/>"""

    /** LINK: Pfeil rechts */
    val LINK = """<polygon points="4,9 16,9 16,6 22,12 16,18 16,15 4,15" fill="currentColor"/>"""

    /** CANCEL: X */
    val CANCEL =
        """<line x1="6" y1="6" x2="18" y2="18" stroke="currentColor" stroke-width="2"/>""" +
            """<line x1="18" y1="6" x2="6" y2="18" stroke="currentColor" stroke-width="2"/>"""

    /** TERMINATE: gefüllter Kreis */
    val TERMINATE = """<circle cx="12" cy="12" r="7" fill="currentColor"/>"""

    /** MULTIPLE (catching): Pentagon (Outline) */
    val MULTIPLE =
        """<polygon points="12,4 20,10 17,20 7,20 4,10" fill="none" stroke="currentColor" stroke-width="1.5"/>"""

    /** MULTIPLE_FILLED (throwing): Pentagon (gefüllt) */
    val MULTIPLE_FILLED =
        """<polygon points="12,4 20,10 17,20 7,20 4,10" fill="currentColor"/>"""

    /** PARALLEL_MULTIPLE: Kreuz (+) */
    val PARALLEL_MULTIPLE =
        """<line x1="12" y1="4" x2="12" y2="20" stroke="currentColor" stroke-width="2"/>""" +
            """<line x1="4" y1="12" x2="20" y2="12" stroke="currentColor" stroke-width="2"/>"""

    /**
     * Gibt das passende SVG-Fragment für eine [EventDefinition] zurück,
     * oder `null` bei [EventDefinition.NONE].
     *
     * @param definition Die Trigger-Definition des Events.
     * @param throwing `true` für THROWING-Events (andere Füll-Stile bei MESSAGE, SIGNAL).
     */
    fun forDefinition(
        definition: EventDefinition,
        throwing: Boolean,
    ): String? =
        when (definition) {
            EventDefinition.NONE -> null
            EventDefinition.MESSAGE -> if (throwing) MESSAGE_FILLED else MESSAGE
            EventDefinition.TIMER -> TIMER
            EventDefinition.ERROR -> ERROR
            EventDefinition.ESCALATION -> if (throwing) ESCALATION else ESCALATION_OUTLINE
            EventDefinition.SIGNAL -> if (throwing) SIGNAL_FILLED else SIGNAL
            EventDefinition.COMPENSATION -> if (throwing) COMPENSATION else COMPENSATION_OUTLINE
            EventDefinition.CONDITIONAL -> CONDITIONAL
            EventDefinition.LINK -> LINK
            EventDefinition.CANCEL -> CANCEL
            EventDefinition.MULTIPLE -> if (throwing) MULTIPLE_FILLED else MULTIPLE
            EventDefinition.PARALLEL_MULTIPLE -> PARALLEL_MULTIPLE
            EventDefinition.TERMINATE -> TERMINATE
        }
}
