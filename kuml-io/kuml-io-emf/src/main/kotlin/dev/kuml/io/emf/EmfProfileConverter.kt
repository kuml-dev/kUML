package dev.kuml.io.emf

import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlStereotype
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import org.eclipse.uml2.uml.Profile
import org.eclipse.uml2.uml.Stereotype
import org.eclipse.uml2.uml.UMLFactory
import org.eclipse.uml2.uml.VisibilityKind

/**
 * Converts UML profiles and stereotypes between kUML's pure Kotlin representation
 * and EMF/UML2 Profile/Stereotype objects.
 *
 * V3.0.30 (deferred from V3.0.16): EMF Profile conversion.
 *
 * ## EMF Profile structure
 * - `Profile` extends `Package` — owns `Stereotype` elements
 * - `Stereotype` extends `Class` — references metaclasses via `Extension`
 * - `Extension` is an `Association` between Stereotype and metaclass
 * - Tag values → `Property` on the Stereotype
 *
 * ## kUML UmlStereotype structure (from V11Types.kt)
 * - `metaclasses: List<String>` — metaclass names (e.g. "Class", "Property")
 * - `tagDefinitions: List<UmlProperty>` — tag value properties
 *
 * ## Metaclass encoding note
 * The EMF `Stereotype.createExtension(metaclass, required)` API requires the
 * metaclass argument to be a real UML metamodel Class with `isMetaclass() == true`
 * (i.e. carrying the `StandardProfile::Metaclass` stereotype). These instances
 * are only available via the `UML.metamodel.uml` resource file bundled in the
 * Eclipse P2 plugin `org.eclipse.uml2.uml.resources`, which is not published on
 * Maven Central.
 *
 * kUML encodes metaclass names using a naming convention on owned attributes:
 * each metaclass is stored as a `PrimitiveType`-typed attribute named
 * `$METACLASS_ATTR_PREFIX<metaclassName>` (e.g. `_kuml_metaclass_Class`).
 * These attributes have no association and are filtered out from tag definitions
 * during `convertStereotypeFromEmf`. This encoding is sufficient for in-memory
 * Profile/Stereotype roundtrip without requiring the Eclipse P2 resources plugin.
 */
public class EmfProfileConverter {
    private val factory = UMLFactory.eINSTANCE

    // ── UmlStereotype → EMF Stereotype ────────────────────────────────────────

    /**
     * Converts a [UmlStereotype] to an EMF [Stereotype] within an EMF [Profile].
     *
     * The stereotype is added as an owned type of the profile. Metaclass names are
     * encoded as special owned attributes (see class-level doc for details).
     * Tag definitions are converted to owned attributes on the stereotype.
     */
    public fun convertStereotypeToEmf(
        stereotype: UmlStereotype,
        profile: Profile,
    ): Stereotype {
        EmfBootstrap.init()
        val emfStereo = profile.createOwnedStereotype(stereotype.name, false)
        emfStereo.visibility = convertVisibilityToEmf(stereotype.visibility)

        // Metaclass names → sentinel attributes with well-known name prefix
        for (metaclassName in stereotype.metaclasses) {
            val sentinelType = factory.createPrimitiveType().also { it.name = metaclassName }
            emfStereo.createOwnedAttribute(METACLASS_ATTR_PREFIX + metaclassName, sentinelType)
        }

        // Tag definitions → owned attributes on the stereotype
        for (tagDef in stereotype.tagDefinitions) {
            val tagType = factory.createPrimitiveType().also { it.name = tagDef.type.name }
            emfStereo.createOwnedAttribute(tagDef.name, tagType)
        }

        return emfStereo
    }

    // ── EMF Stereotype → UmlStereotype ────────────────────────────────────────

    /**
     * Converts an EMF [Stereotype] to a [UmlStereotype].
     *
     * Metaclass names are recovered from owned attributes whose names start with
     * [METACLASS_ATTR_PREFIX]. Tag definitions are all other non-association attributes.
     */
    public fun convertStereotypeFromEmf(emfStereo: Stereotype): UmlStereotype {
        EmfBootstrap.init()
        val nonAssocAttrs = emfStereo.ownedAttributes.filter { attr -> attr.association == null }

        val metaclasses =
            nonAssocAttrs
                .filter { attr -> attr.name?.startsWith(METACLASS_ATTR_PREFIX) == true }
                .mapNotNull { attr -> attr.name?.removePrefix(METACLASS_ATTR_PREFIX) }
                .filter { it.isNotBlank() }

        val tagDefs =
            nonAssocAttrs
                .filter { attr -> attr.name?.startsWith(METACLASS_ATTR_PREFIX) != true }
                .map { attr ->
                    UmlProperty(
                        id = "${emfStereo.name ?: "?"}.${attr.name ?: "?"}",
                        name = attr.name ?: "unnamed",
                        type = UmlTypeRef(name = attr.type?.name ?: ""),
                    )
                }

        return UmlStereotype(
            id = emfStereo.name ?: "unnamed",
            name = emfStereo.name ?: "Unnamed",
            visibility = convertVisibilityFromEmf(emfStereo.visibility),
            metaclasses = metaclasses,
            tagDefinitions = tagDefs,
        )
    }

    // ── Profile roundtrip ──────────────────────────────────────────────────────

    /**
     * Creates an EMF [Profile] containing all stereotypes from a [UmlStereotype] list.
     *
     * @param profileName  Name for the EMF Profile (e.g. "autosar", "javaee")
     * @param stereotypes  List of kUML stereotypes to convert
     */
    public fun createEmfProfile(
        profileName: String,
        stereotypes: List<UmlStereotype>,
    ): Profile {
        EmfBootstrap.init()
        val profile = factory.createProfile().also { it.name = profileName }
        for (stereo in stereotypes) {
            convertStereotypeToEmf(stereo, profile)
        }
        return profile
    }

    /**
     * Extracts all [UmlStereotype] instances from an EMF [Profile].
     */
    public fun extractStereotypes(profile: Profile): List<UmlStereotype> {
        EmfBootstrap.init()
        return profile.ownedStereotypes.map { convertStereotypeFromEmf(it) }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun convertVisibilityToEmf(vis: Visibility): VisibilityKind =
        when (vis) {
            Visibility.PRIVATE -> VisibilityKind.PRIVATE_LITERAL
            Visibility.PROTECTED -> VisibilityKind.PROTECTED_LITERAL
            Visibility.PACKAGE -> VisibilityKind.PACKAGE_LITERAL
            Visibility.PUBLIC -> VisibilityKind.PUBLIC_LITERAL
        }

    private fun convertVisibilityFromEmf(vis: VisibilityKind): Visibility =
        when (vis) {
            VisibilityKind.PRIVATE_LITERAL -> Visibility.PRIVATE
            VisibilityKind.PROTECTED_LITERAL -> Visibility.PROTECTED
            VisibilityKind.PACKAGE_LITERAL -> Visibility.PACKAGE
            else -> Visibility.PUBLIC
        }

    public companion object {
        /**
         * Attribute name prefix used to encode metaclass names as owned attributes on
         * an EMF Stereotype. Attributes whose name starts with this prefix are metaclass
         * markers, not tag definitions.
         *
         * Example: metaclass "Class" → attribute name "_kuml_metaclass_Class".
         */
        public const val METACLASS_ATTR_PREFIX: String = "_kuml_metaclass_"
    }
}
