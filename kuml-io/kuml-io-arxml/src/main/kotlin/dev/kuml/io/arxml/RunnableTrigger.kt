package dev.kuml.io.arxml

/**
 * Maps AUTOSAR runnable trigger event types onto kUML metadata.
 *
 * In AUTOSAR Classic, trigger events live under `SWC-INTERNAL-BEHAVIOR/EVENTS` and
 * reference the activated runnable via `START-ON-EVENT-REF`. This enum captures the
 * supported trigger element local-names so that the importer can join events to
 * runnables and the exporter can emit the correct element name.
 *
 * Stored on [dev.kuml.uml.UmlOperation] as `metadata["trigger"] = KumlMetaValue.Text(name)`.
 *
 * V3.1.34 — initial implementation.
 */
public enum class RunnableTrigger(
    /** AUTOSAR event element local-name under SWC-INTERNAL-BEHAVIOR/EVENTS. */
    public val arxmlElementName: String,
) {
    TIMING(ArxmlSchema.ELEM_TIMING_EVENT),
    DATA_RECEIVED(ArxmlSchema.ELEM_DATA_RECEIVED_EVENT),
    OPERATION_INVOKED(ArxmlSchema.ELEM_OPERATION_INVOKED_EVENT),
    INIT(ArxmlSchema.ELEM_INIT_EVENT),
    MODE_SWITCH(ArxmlSchema.ELEM_SWC_MODE_SWITCH_EVENT),
    UNKNOWN("UNKNOWN-EVENT"),
    ;

    public companion object {
        /**
         * Returns the [RunnableTrigger] for the given AUTOSAR event element [localName],
         * or [UNKNOWN] if the element name is not recognised.
         */
        public fun fromArxmlElementName(localName: String): RunnableTrigger =
            entries.firstOrNull { it.arxmlElementName == localName } ?: UNKNOWN
    }
}
