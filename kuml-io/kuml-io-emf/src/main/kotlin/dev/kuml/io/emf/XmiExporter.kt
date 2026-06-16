package dev.kuml.io.emf

import dev.kuml.core.model.KumlModel
import java.io.File

/**
 * Öffentliche Fassade für KumlModel → XMI.
 *
 * Wird von der CLI via Reflection geladen.
 *
 * Aufruf-Kette:
 *   KumlModel → [UmlToEmfConverter] → EmfModel → [XmiWriter] → XMI-Datei
 *
 * V3.0.17: Initiale Implementierung.
 */
public class XmiExporter {
    private val converter = UmlToEmfConverter()
    private val writer = XmiWriter()

    public fun export(
        model: KumlModel,
        outputFile: File,
    ) {
        val emfModel = converter.convert(model)
        writer.write(emfModel, outputFile)
    }
}
