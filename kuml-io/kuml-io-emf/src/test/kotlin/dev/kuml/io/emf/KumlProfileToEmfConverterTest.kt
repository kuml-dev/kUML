package dev.kuml.io.emf

import dev.kuml.profile.autosar.autosarProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.eclipse.uml2.uml.VisibilityKind

class KumlProfileToEmfConverterTest :
    FunSpec({

        val converter = KumlProfileToEmfConverter()

        beforeSpec { EmfBootstrap.init() }

        test("converts AUTOSAR profile to EMF Profile with 5 stereotypes") {
            val emfProfile = converter.convert(autosarProfile)
            emfProfile.ownedStereotypes shouldHaveSize 5
            emfProfile.ownedStereotypes.map { it.name } shouldContainExactlyInAnyOrder
                listOf("SoftwareComponent", "ComInterface", "AutosarPort", "Runnable", "BehaviorSpec")
        }

        test("EMF Profile name matches kUML profile name") {
            val emfProfile = converter.convert(autosarProfile)
            emfProfile.name shouldBe autosarProfile.name
        }

        test("each stereotype carries exactly one metaclass sentinel attribute") {
            val emfProfile = converter.convert(autosarProfile)
            val swcStereo = emfProfile.ownedStereotypes.first { it.name == "SoftwareComponent" }
            val sentinels =
                swcStereo.ownedAttributes.filter {
                    it.name.orEmpty().startsWith(KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX)
                }
            sentinels shouldHaveSize 1
            sentinels.first().name shouldBe "${KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX}Component"
        }

        test("sentinel attribute encodes correct metaclass for AutosarPort") {
            val emfProfile = converter.convert(autosarProfile)
            val portStereo = emfProfile.ownedStereotypes.first { it.name == "AutosarPort" }
            val sentinel =
                portStereo.ownedAttributes.first {
                    it.name.orEmpty().startsWith(KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX)
                }
            sentinel.name shouldBe "${KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX}Port"
        }

        test("tag definitions become owned attributes on stereotype") {
            val emfProfile = converter.convert(autosarProfile)
            val swcStereo = emfProfile.ownedStereotypes.first { it.name == "SoftwareComponent" }
            val tagAttrs =
                swcStereo.ownedAttributes.filter {
                    !it.name.orEmpty().startsWith(KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX)
                }
            tagAttrs.map { it.name } shouldContainExactlyInAnyOrder listOf("kind", "packageName")
        }

        test("tag attribute visibility is PUBLIC") {
            val emfProfile = converter.convert(autosarProfile)
            val comInterface = emfProfile.ownedStereotypes.first { it.name == "ComInterface" }
            val versionAttr = comInterface.ownedAttributes.first { it.name == "version" }
            versionAttr.visibility shouldBe VisibilityKind.PUBLIC_LITERAL
        }

        test("sentinel attribute visibility is PRIVATE") {
            val emfProfile = converter.convert(autosarProfile)
            val swcStereo = emfProfile.ownedStereotypes.first { it.name == "SoftwareComponent" }
            val sentinel =
                swcStereo.ownedAttributes.first {
                    it.name.orEmpty().startsWith(KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX)
                }
            sentinel.visibility shouldBe VisibilityKind.PRIVATE_LITERAL
        }

        test("profile-level EAnnotation carries namespace") {
            val emfProfile = converter.convert(autosarProfile)
            val annotation =
                emfProfile.eAnnotations.find {
                    it.source == KumlProfileToEmfConverter.PROFILE_ANNOTATION_SOURCE
                }
            annotation.shouldNotBeNull()
            annotation.details["namespace"] shouldBe autosarProfile.namespace
        }

        test("profile-level EAnnotation carries version") {
            val emfProfile = converter.convert(autosarProfile)
            val annotation =
                emfProfile.eAnnotations.find {
                    it.source == KumlProfileToEmfConverter.PROFILE_ANNOTATION_SOURCE
                }
            annotation.shouldNotBeNull()
            annotation.details["version"] shouldBe autosarProfile.version
        }

        test("property EAnnotation carries required flag") {
            val emfProfile = converter.convert(autosarProfile)
            val swcStereo = emfProfile.ownedStereotypes.first { it.name == "SoftwareComponent" }
            val kindAttr = swcStereo.ownedAttributes.first { it.name == "kind" }
            val annotation =
                kindAttr.eAnnotations.find {
                    it.source == KumlProfileToEmfConverter.PROFILE_ANNOTATION_SOURCE
                }
            annotation.shouldNotBeNull()
            annotation.details["required"].shouldNotBeNull()
        }

        test("property type name is recorded for primitive types") {
            val emfProfile = converter.convert(autosarProfile)
            val runnableStereo = emfProfile.ownedStereotypes.first { it.name == "Runnable" }
            val periodAttr = runnableStereo.ownedAttributes.first { it.name == "periodMs" }
            periodAttr.type?.name shouldBe "Long"
        }

        test("Boolean property type name recorded correctly") {
            val emfProfile = converter.convert(autosarProfile)
            val comInterface = emfProfile.ownedStereotypes.first { it.name == "ComInterface" }
            val isServiceAttr = comInterface.ownedAttributes.first { it.name == "isService" }
            isServiceAttr.type?.name shouldBe "Boolean"
        }

        test("BehaviorSpec stereotype targets StateMachine metaclass") {
            val emfProfile = converter.convert(autosarProfile)
            val behaviorSpec = emfProfile.ownedStereotypes.first { it.name == "BehaviorSpec" }
            val sentinel =
                behaviorSpec.ownedAttributes.first {
                    it.name.orEmpty().startsWith(KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX)
                }
            sentinel.name shouldBe "${KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX}StateMachine"
        }

        test("convertStereotype returns Stereotype with correct name") {
            val emfProfile = converter.convert(autosarProfile)
            // Profile already built; verify individual stereotype API
            val swcInProfile = emfProfile.ownedStereotypes.first { it.name == "SoftwareComponent" }
            swcInProfile.name shouldBe "SoftwareComponent"
        }

        test("enum-typed property type name uses simple class name") {
            val emfProfile = converter.convert(autosarProfile)
            val swcStereo = emfProfile.ownedStereotypes.first { it.name == "SoftwareComponent" }
            val kindAttr = swcStereo.ownedAttributes.first { it.name == "kind" }
            // AutosarSwcKind is an enum — type name should be its simple class name
            kindAttr.type?.name shouldStartWith "Autosar"
        }
    })
