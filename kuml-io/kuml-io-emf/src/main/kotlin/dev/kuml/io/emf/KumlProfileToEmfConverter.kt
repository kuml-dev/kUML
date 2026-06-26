package dev.kuml.io.emf

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlStereotype
import dev.kuml.profile.StereotypeProperty
import org.eclipse.emf.ecore.EcoreFactory
import org.eclipse.uml2.uml.Profile
import org.eclipse.uml2.uml.Stereotype

/**
 * Converts a [KumlProfile] into an Eclipse UML2 [Profile] model.
 *
 * ## Metaclass encoding
 * The Eclipse P2 resource `org.eclipse.uml2.uml.resources` (which carries the real UML
 * metamodel with `isMetaclass() == true` Class instances) is **not** available on Maven
 * Central, so `Stereotype.createExtension(metaclass, required)` cannot be called with a
 * genuine metaclass proxy. Instead, each stereotype's `targetMetaclass` is encoded as a
 * single owned attribute whose name is [METACLASS_ATTR_PREFIX] + metaclass-name and whose
 * type is a freshly-created PrimitiveType named after the metaclass. This encoding survives
 * XMI round-trips and is fully decoded by [EmfProfileToKumlConverter].
 *
 * ## EAnnotation metadata
 * Profile-level and property-level metadata (namespace, version, description, extendsProfiles,
 * required, default, min) is stored in EAnnotations with source [PROFILE_ANNOTATION_SOURCE].
 *
 * V3.1.41: Initial implementation.
 */
public class KumlProfileToEmfConverter {
    private val ecoreFactory: EcoreFactory = EcoreFactory.eINSTANCE

    // Cache of owned PrimitiveTypes per profile to avoid duplicates
    private val ownedTypes = mutableMapOf<String, org.eclipse.uml2.uml.PrimitiveType>()

    public companion object {
        /** Prefix for sentinel attributes encoding a stereotype's target metaclass. */
        public const val METACLASS_ATTR_PREFIX: String = "_kuml_metaclass_"

        /** EAnnotation source URI for kUML-specific metadata. */
        public const val PROFILE_ANNOTATION_SOURCE: String = "dev.kuml.profile"
    }

    /**
     * Converts [profile] to an Eclipse UML2 [Profile].
     * [EmfBootstrap.init] is called automatically.
     */
    public fun convert(profile: KumlProfile): Profile {
        EmfBootstrap.init()
        ownedTypes.clear()
        val emfProfile =
            org.eclipse.uml2.uml.UMLFactory.eINSTANCE
                .createProfile()
        emfProfile.name = profile.name

        // Profile-level EAnnotation: namespace, version, description, extends
        val profileAnnotation = ecoreFactory.createEAnnotation()
        profileAnnotation.source = PROFILE_ANNOTATION_SOURCE
        profileAnnotation.details.put("namespace", profile.namespace)
        profileAnnotation.details.put("version", profile.version)
        profileAnnotation.details.put("description", profile.description)
        if (profile.extendsProfiles.isNotEmpty()) {
            profileAnnotation.details.put("extendsProfiles", profile.extendsProfiles.joinToString(","))
        }
        emfProfile.eAnnotations.add(profileAnnotation)

        for (stereotype in profile.stereotypes) {
            convertStereotype(stereotype, emfProfile)
        }

        return emfProfile
    }

    /**
     * Converts a single [KumlStereotype] and adds it to [emfProfile].
     */
    public fun convertStereotype(
        stereotype: KumlStereotype,
        emfProfile: Profile,
    ): Stereotype {
        val emfStereo = emfProfile.createOwnedStereotype(stereotype.name, false)
        emfStereo.visibility = org.eclipse.uml2.uml.VisibilityKind.PUBLIC_LITERAL

        // Encode specializes in an EAnnotation if present
        if (stereotype.specializes != null) {
            val stereoAnnotation = ecoreFactory.createEAnnotation()
            stereoAnnotation.source = PROFILE_ANNOTATION_SOURCE
            stereoAnnotation.details.put("specializes", stereotype.specializes)
            emfStereo.eAnnotations.add(stereoAnnotation)
        }

        // Encode targetMetaclass as sentinel attribute
        val metaclassName = stereotype.targetMetaclass.name
        val metaclassType =
            ownedTypes.getOrPut("_mc_$metaclassName") {
                emfProfile.createOwnedPrimitiveType(metaclassName)
            }
        val metaclassAttr =
            emfStereo.createOwnedAttribute(
                METACLASS_ATTR_PREFIX + metaclassName,
                metaclassType,
            )
        metaclassAttr.visibility = org.eclipse.uml2.uml.VisibilityKind.PRIVATE_LITERAL

        // Encode each tag property
        for (prop in stereotype.properties) {
            convertProperty(prop, emfStereo, emfProfile)
        }

        return emfStereo
    }

    private fun convertProperty(
        prop: StereotypeProperty<*>,
        emfStereo: Stereotype,
        emfProfile: Profile,
    ) {
        val typeName = resolveTypeName(prop)
        val propType =
            ownedTypes.getOrPut(typeName) {
                emfProfile.createOwnedPrimitiveType(typeName)
            }

        val attr = emfStereo.createOwnedAttribute(prop.name, propType)
        attr.visibility = org.eclipse.uml2.uml.VisibilityKind.PUBLIC_LITERAL

        // Store metadata in EAnnotation
        val annotation = ecoreFactory.createEAnnotation()
        annotation.source = PROFILE_ANNOTATION_SOURCE
        annotation.details.put("required", prop.required.toString())
        if (prop.default != null) {
            annotation.details.put("default", prop.default.toString())
        }
        if (prop.min != null) {
            annotation.details.put("min", prop.min.toString())
        }
        attr.eAnnotations.add(annotation)
    }

    private fun resolveTypeName(prop: StereotypeProperty<*>): String {
        val kClass = prop.type
        return when (kClass) {
            String::class -> "String"
            Int::class -> "Int"
            Long::class -> "Long"
            Double::class -> "Double"
            Boolean::class -> "Boolean"
            List::class -> "List"
            else -> kClass.simpleName ?: "String"
        }
    }
}
