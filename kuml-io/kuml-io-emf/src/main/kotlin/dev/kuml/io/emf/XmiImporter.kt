package dev.kuml.io.emf

import dev.kuml.core.model.KumlModel
import java.io.File

/**
 * Öffentliche Fassade für XMI → KumlModel.
 *
 * Wird von der CLI via Reflection geladen (um Native-Image-Kompatibilität
 * von kuml-cli zu wahren — EMF ist JVM-only).
 *
 * Aufruf-Kette:
 *   XMI-Datei → [XmiReader] → EmfModel → [XmiToolFilter.normalize] → [EmfToUmlConverter] → KumlModel
 *
 * V3.0.17: Initiale Implementierung.
 */
public class XmiImporter {
    private val reader = XmiReader()
    private val converter = EmfToUmlConverter()

    public fun import(file: File): KumlModel {
        val emfModel = reader.read(file)
        XmiToolFilter.normalize(emfModel)
        return converter.convert(emfModel)
    }
}
