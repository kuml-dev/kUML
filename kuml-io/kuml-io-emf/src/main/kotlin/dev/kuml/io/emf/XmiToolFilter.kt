package dev.kuml.io.emf

import org.eclipse.uml2.uml.Model as EmfModel

/**
 * Bereinigt tool-spezifische XMI-Quirks vor der Konvertierung.
 *
 * Bekannte Fälle:
 * - **Enterprise Architect (EA)**: EAStub-Elemente (Platzhalter für externe Referenzen),
 *   Leerstrings als Namen, eigenständige Visibility-Strings.
 * - **Papyrus**: `notation:`-Layer in separater Resource (wird ignoriert — liegt nicht im
 *   UML-Resource, sondern in `.notation`-Datei).
 * - **MagicDraw/Cameo**: Eigenständige TaggedValue-Strukturen außerhalb des UML-Profils.
 * - **Visual Paradigm**: Versionspräfixe im Modellnamen.
 *
 * V3.0.17: minimale Filter-Schicht — keine tiefe strukturelle Transformation.
 */
public object XmiToolFilter {
    /**
     * Normalisiert das Modell nach dem Lesen.
     * Gibt dasselbe Objekt zurück (in-place Mutation).
     */
    public fun normalize(model: EmfModel): EmfModel {
        // EA: Modellname enthält manchmal Präfixe wie "EA_Model_"
        model.name = model.name?.removePrefix("EA_Model_") ?: model.name
        // Visual Paradigm: Modellname enthält manchmal Version "VP12."
        model.name = model.name?.replace(Regex("^VP\\d+\\."), "") ?: model.name
        return model
    }
}
