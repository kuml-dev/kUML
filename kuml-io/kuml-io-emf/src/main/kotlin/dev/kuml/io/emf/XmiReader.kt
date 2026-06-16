package dev.kuml.io.emf

import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl
import org.eclipse.uml2.uml.UMLPackage
import java.io.File
import org.eclipse.uml2.uml.Model as EmfModel

/**
 * Liest XMI-Dateien und gibt ein EMF-Modell zurück.
 *
 * Unterstützt sowohl Datei-Pfade als auch Strings (für Tests).
 * Tool-spezifische Quirks (EA EAStub, Papyrus notation:) werden
 * via [XmiToolFilter] vor der Konvertierung bereinigt.
 *
 * V3.0.17: Initiale Implementierung — EA, Papyrus, Standard-UML2-XMI.
 */
public class XmiReader {
    public fun read(file: File): EmfModel {
        EmfBootstrap.init()
        val resourceSet = ResourceSetImpl()
        resourceSet.packageRegistry[UMLPackage.eNS_URI] = UMLPackage.eINSTANCE
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["xmi"] = XMIResourceFactoryImpl()
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["uml"] = XMIResourceFactoryImpl()
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["*"] = XMIResourceFactoryImpl()
        val resource = resourceSet.getResource(URI.createFileURI(file.absolutePath), true)
        return resource.contents.filterIsInstance<EmfModel>().firstOrNull()
            ?: error("No UML Model found in XMI: ${file.name}")
    }

    /** Für Tests: liest XMI aus einem String (keine Datei nötig). */
    public fun readFromString(xmi: String): EmfModel {
        val tmpFile =
            kotlin.io.path.createTempFile("kuml-xmi-", ".xmi").toFile().also {
                it.writeText(xmi)
                it.deleteOnExit()
            }
        return read(tmpFile)
    }
}
