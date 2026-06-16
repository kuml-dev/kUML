package dev.kuml.io.emf

import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl
import org.eclipse.uml2.uml.UMLPackage
import java.io.File
import org.eclipse.uml2.uml.Model as EmfModel

/**
 * Schreibt ein EMF-Modell als XMI-Datei oder XMI-String.
 *
 * V3.0.17: Initiale Implementierung — Standard-UML2-XMI-Ausgabe.
 */
public class XmiWriter {
    public fun write(
        emfModel: EmfModel,
        outputFile: File,
    ) {
        EmfBootstrap.init()
        outputFile.parentFile?.mkdirs()
        val resourceSet = ResourceSetImpl()
        resourceSet.packageRegistry[UMLPackage.eNS_URI] = UMLPackage.eINSTANCE
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["xmi"] = XMIResourceFactoryImpl()
        val resource = resourceSet.createResource(URI.createFileURI(outputFile.absolutePath))
        resource.contents.add(emfModel)
        resource.save(null)
    }

    /** Für Tests: gibt XMI als String zurück. Schreibt in eine temporäre Datei und liest zurück. */
    public fun writeToString(emfModel: EmfModel): String {
        val tmpFile =
            kotlin.io.path
                .createTempFile("kuml-xmi-writer-", ".xmi")
                .toFile()
        tmpFile.deleteOnExit()
        write(emfModel, tmpFile)
        return tmpFile.readText(Charsets.UTF_8)
    }
}
