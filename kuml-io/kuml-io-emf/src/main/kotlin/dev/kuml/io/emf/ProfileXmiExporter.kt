package dev.kuml.io.emf

import dev.kuml.profile.KumlProfile
import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl
import org.eclipse.uml2.uml.UMLPackage
import java.io.File

/**
 * Exports a [KumlProfile] to an Eclipse UML2 `.profile.uml` XMI file.
 *
 * The content of the XMI file is an `uml:Profile` root element (not `uml:Model`).
 * Existing XMI tools for models ([XmiWriter]) cannot be reused directly because they
 * put a `Model` — not a `Profile` — into the resource.
 *
 * A public no-arg constructor is required so [ExportCommand] can load this class
 * reflectively on the JVM (native image degrades to FORMAT_NOT_AVAILABLE = 24 when
 * the class is absent).
 *
 * V3.1.41: Initial implementation.
 */
public class ProfileXmiExporter {
    private val converter: KumlProfileToEmfConverter = KumlProfileToEmfConverter()

    /**
     * Converts [profile] and writes the result as a `.profile.uml` XMI file to [outputFile].
     * Parent directories are created automatically.
     */
    public fun export(
        profile: KumlProfile,
        outputFile: File,
    ) {
        EmfBootstrap.init()
        outputFile.parentFile?.mkdirs()

        val emfProfile = converter.convert(profile)

        val resourceSet = ResourceSetImpl()
        resourceSet.packageRegistry[UMLPackage.eNS_URI] = UMLPackage.eINSTANCE
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["uml"] = XMIResourceFactoryImpl()
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["profile.uml"] = XMIResourceFactoryImpl()
        resourceSet.resourceFactoryRegistry.extensionToFactoryMap["xmi"] = XMIResourceFactoryImpl()

        val resource = resourceSet.createResource(URI.createFileURI(outputFile.absolutePath))
        resource.contents.add(emfProfile)
        resource.save(null)
    }

    /**
     * Convenience helper for tests: exports to a temporary file and returns the XML text.
     */
    public fun writeToString(profile: KumlProfile): String {
        val tmpFile =
            kotlin.io.path
                .createTempFile("kuml-profile-", ".profile.uml")
                .toFile()
        tmpFile.deleteOnExit()
        export(profile, tmpFile)
        return tmpFile.readText(Charsets.UTF_8)
    }
}
