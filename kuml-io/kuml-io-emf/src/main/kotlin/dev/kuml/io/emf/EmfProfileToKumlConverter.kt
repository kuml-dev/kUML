package dev.kuml.io.emf

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlStereotype
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.ProfileBuilder
import dev.kuml.profile.builder.StereotypeBuilder
import dev.kuml.profile.builder.profile
import org.eclipse.uml2.uml.Profile
import org.eclipse.uml2.uml.Stereotype

/**
 * Converts an Eclipse UML2 [Profile] back into a [KumlProfile].
 *
 * Reads the encoding written by [KumlProfileToEmfConverter]:
 * - Profile-level EAnnotation (source = [KumlProfileToEmfConverter.PROFILE_ANNOTATION_SOURCE])
 *   carries namespace, version, description, extendsProfiles.
 * - Sentinel attribute named `_kuml_metaclass_<Name>` encodes each stereotype's
 *   [UmlMetaclass]. Remaining owned attributes are tag properties.
 * - Property-level EAnnotation carries required, default, min.
 *
 * ## Enum type limitation
 * Tag properties whose original Kotlin type is an enum class cannot be reconstructed to
 * their original [kotlin.reflect.KClass] because the enum class may not be on the
 * classpath at import time. Such properties round-trip as `String::class`. This is a
 * documented limitation and is asserted in the test suite.
 *
 * V3.1.41: Initial implementation.
 */
public class EmfProfileToKumlConverter {
    private val annotationSource = KumlProfileToEmfConverter.PROFILE_ANNOTATION_SOURCE
    private val metaclassPrefix = KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX

    /**
     * Converts [emfProfile] to a [KumlProfile].
     * Throws [IllegalArgumentException] on structural problems (unknown metaclass etc.).
     */
    public fun convert(emfProfile: Profile): KumlProfile {
        EmfBootstrap.init()

        // Read profile-level metadata from EAnnotation
        val profileAnnotation = emfProfile.eAnnotations.find { it.source == annotationSource }
        val namespace = profileAnnotation?.details?.get("namespace") ?: emfProfile.name ?: ""
        val version = profileAnnotation?.details?.get("version") ?: "1.0.0"
        val description = profileAnnotation?.details?.get("description") ?: ""
        val extendsRaw = profileAnnotation?.details?.get("extendsProfiles") ?: ""
        val extendsList = if (extendsRaw.isBlank()) emptyList() else extendsRaw.split(",")

        return profile(emfProfile.name ?: "") {
            this.namespace = namespace
            this.version = version
            this.description = description
            for (ns in extendsList) {
                extends(ns)
            }
            for (emfStereo in emfProfile.ownedStereotypes) {
                buildStereotype(emfStereo)
            }
        }
    }

    /**
     * Converts a single [Stereotype] and adds it to the [ProfileBuilder] via [stereotype].
     */
    public fun convertStereotype(emfStereo: Stereotype): KumlStereotype {
        val metaclass = extractMetaclass(emfStereo)
        val tagAttrs =
            emfStereo.ownedAttributes.filter { attr ->
                !attr.name.orEmpty().startsWith(metaclassPrefix)
            }
        val specializesValue =
            emfStereo.eAnnotations
                .find { it.source == annotationSource }
                ?.details
                ?.get("specializes")

        return KumlStereotype(
            name = emfStereo.name ?: "",
            targetMetaclass = metaclass,
            properties =
                tagAttrs.map { attr ->
                    val typeName = attr.type?.name ?: "String"
                    val propAnnotation = attr.eAnnotations.find { it.source == annotationSource }
                    val required = propAnnotation?.details?.get("required")?.toBoolean() ?: true
                    val defaultVal = propAnnotation?.details?.get("default")
                    val min = propAnnotation?.details?.get("min")?.toIntOrNull()

                    buildStringProperty(
                        name = attr.name ?: "",
                        typeName = typeName,
                        required = required,
                        defaultVal = defaultVal,
                        min = min,
                    )
                },
            specializes = specializesValue,
        )
    }

    private fun ProfileBuilder.buildStereotype(emfStereo: Stereotype) {
        val metaclass = extractMetaclass(emfStereo)
        val tagAttrs =
            emfStereo.ownedAttributes.filter { attr ->
                !attr.name.orEmpty().startsWith(metaclassPrefix)
            }
        val specializesValue =
            emfStereo.eAnnotations
                .find { it.source == annotationSource }
                ?.details
                ?.get("specializes")

        stereotype(emfStereo.name ?: "") {
            extends(metaclass)
            specializes = specializesValue
            for (attr in tagAttrs) {
                val typeName = attr.type?.name ?: "String"
                val propAnnotation = attr.eAnnotations.find { it.source == annotationSource }
                val required = propAnnotation?.details?.get("required")?.toBoolean() ?: true
                val defaultVal = propAnnotation?.details?.get("default")
                val min = propAnnotation?.details?.get("min")?.toIntOrNull()
                addTypedProperty(typeName, attr.name ?: "", required, defaultVal, min)
            }
        }
    }

    private fun StereotypeBuilder.addTypedProperty(
        typeName: String,
        propName: String,
        required: Boolean,
        defaultVal: String?,
        min: Int?,
    ) {
        when (typeName) {
            "Int" ->
                property<Int>(propName) {
                    this.required = required
                    this.default = defaultVal?.toIntOrNull()
                    this.min = min
                }
            "Long" ->
                property<Long>(propName) {
                    this.required = required
                    this.default = defaultVal?.toLongOrNull()
                    this.min = min
                }
            "Double" ->
                property<Double>(propName) {
                    this.required = required
                    this.default = defaultVal?.toDoubleOrNull()
                    this.min = min
                }
            "Boolean" ->
                property<Boolean>(propName) {
                    this.required = required
                    this.default = defaultVal?.toBooleanStrictOrNull()
                    this.min = min
                }
            // String, List, unknown types, and enum fallback all use String
            else ->
                property<String>(propName) {
                    this.required = required
                    this.default = defaultVal
                    this.min = min
                }
        }
    }

    private fun extractMetaclass(emfStereo: Stereotype): UmlMetaclass {
        val sentinelAttr =
            emfStereo.ownedAttributes.firstOrNull { attr ->
                attr.name.orEmpty().startsWith(metaclassPrefix)
            } ?: throw IllegalArgumentException(
                "Stereotype '${emfStereo.name}' has no metaclass sentinel attribute ($metaclassPrefix*). " +
                    "Was this file created by KumlProfileToEmfConverter?",
            )
        val metaclassName = sentinelAttr.name!!.removePrefix(metaclassPrefix)
        return try {
            UmlMetaclass.valueOf(metaclassName)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Stereotype '${emfStereo.name}': unknown UmlMetaclass '$metaclassName'. " +
                    "Known values: ${UmlMetaclass.entries.joinToString()}.",
                e,
            )
        }
    }

    /**
     * Builds a [dev.kuml.profile.StereotypeProperty] typed as String — helper for internal
     * callers that already matched type outside the DSL context.
     */
    private fun buildStringProperty(
        name: String,
        typeName: String,
        required: Boolean,
        defaultVal: String?,
        min: Int?,
    ): dev.kuml.profile.StereotypeProperty<*> =
        when (typeName) {
            "Int" ->
                dev.kuml.profile.StereotypeProperty(
                    name = name,
                    type = Int::class,
                    default = defaultVal?.toIntOrNull(),
                    required = required,
                    min = min,
                )
            "Long" ->
                dev.kuml.profile.StereotypeProperty(
                    name = name,
                    type = Long::class,
                    default = defaultVal?.toLongOrNull(),
                    required = required,
                    min = min,
                )
            "Double" ->
                dev.kuml.profile.StereotypeProperty(
                    name = name,
                    type = Double::class,
                    default = defaultVal?.toDoubleOrNull(),
                    required = required,
                    min = min,
                )
            "Boolean" ->
                dev.kuml.profile.StereotypeProperty(
                    name = name,
                    type = Boolean::class,
                    default = defaultVal?.toBooleanStrictOrNull(),
                    required = required,
                    min = min,
                )
            else ->
                dev.kuml.profile.StereotypeProperty(
                    name = name,
                    type = String::class,
                    default = defaultVal,
                    required = required,
                    min = min,
                )
        }
}
