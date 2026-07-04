package dev.kuml.io.emf

import dev.kuml.profile.KumlProfile
import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl
import org.eclipse.uml2.uml.Profile
import org.eclipse.uml2.uml.UMLPackage
import java.io.File

/**
 * Imports an Eclipse UML2 `.profile.uml` XMI file and returns a [KumlProfile].
 *
 * Two entry points:
 * - [import]: throws on any error (structural or I/O).
 * - [importResult]: wraps all errors in [ProfileResult.Failure] — use this for
 *   malformed-file scenarios where you prefer a value over an exception.
 *
 * Note: [XmiReader] filters for `uml:Model` and therefore cannot be reused here;
 * this class filters for `uml:Profile` instead.
 *
 * V3.1.41: Initial implementation.
 */
public class ProfileXmiImporter {
    private val converter: EmfProfileToKumlConverter = EmfProfileToKumlConverter()

    /**
     * Reads [file] and returns the contained [KumlProfile].
     * @throws IllegalStateException if the file contains no `uml:Profile` root element.
     * @throws IllegalArgumentException if the profile structure is malformed.
     */
    public fun import(file: File): KumlProfile {
        EmfBootstrap.init()

        val resourceSet = ResourceSetImpl()
        resourceSet.packageRegistry[UMLPackage.eNS_URI] = UMLPackage.eINSTANCE
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["uml"] = XMIResourceFactoryImpl()
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["profile.uml"] = XMIResourceFactoryImpl()
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["xmi"] = XMIResourceFactoryImpl()
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["*"] = XMIResourceFactoryImpl()

        // XXE hardening: create the resource without eager load, then load with a
        // secured SAX parser (no DOCTYPE, no external entities). Using the
        // getResource(uri, true) overload would load with default options.
        val resource = resourceSet.createResource(URI.createFileURI(file.absolutePath))
        resource.load(EmfXmlSecurity.secureLoadOptions())
        val emfProfile =
            resource.contents.filterIsInstance<Profile>().firstOrNull()
                ?: error("No uml:Profile found in XMI: ${file.name}")

        return converter.convert(emfProfile)
    }

    /**
     * Like [import] but returns [ProfileResult.Failure] instead of throwing on any error.
     * Use this when the input file may be malformed or structurally incompatible.
     */
    public fun importResult(file: File): ProfileResult =
        try {
            ProfileResult.Success(import(file))
        } catch (t: Throwable) {
            ProfileResult.Failure(
                message = "Failed to import profile from '${file.name}': ${t.message}",
                cause = t,
            )
        }
}
