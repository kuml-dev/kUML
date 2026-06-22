package dev.kuml.io.bpmn

import dev.kuml.bpmn.model.BpmnModel
import java.io.InputStream

/**
 * Convenience entry-point for BPMN 2.0 XML import and export.
 *
 * Delegates to [BpmnXmlExporter] and [BpmnXmlImporter].
 *
 * V3.1.7 — BPMN 2.0 XML Import/Export
 */
public object BpmnXml {
    /** Serializes [model] to a BPMN 2.0 XML string. */
    public fun export(model: BpmnModel): String = BpmnXmlExporter().export(model)

    /** Parses a BPMN 2.0 XML string and returns a [BpmnModel]. */
    public fun import(xml: String): BpmnModel = BpmnXmlImporter().import(xml)

    /** Parses a BPMN 2.0 XML [InputStream] and returns a [BpmnModel]. */
    public fun import(stream: InputStream): BpmnModel = BpmnXmlImporter().import(stream)
}
