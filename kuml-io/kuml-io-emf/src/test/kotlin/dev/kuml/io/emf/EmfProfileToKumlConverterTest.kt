package dev.kuml.io.emf

import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.autosar.autosarProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class EmfProfileToKumlConverterTest :
    FunSpec({

        val toEmf = KumlProfileToEmfConverter()
        val fromEmf = EmfProfileToKumlConverter()

        beforeSpec { EmfBootstrap.init() }

        test("extracts 5 stereotypes back from EMF Profile") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            kumlProfile.stereotypes shouldHaveSize 5
        }

        test("extracts correct stereotype names") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            kumlProfile.stereotypes.map { it.name } shouldContainExactlyInAnyOrder
                listOf("SoftwareComponent", "ComInterface", "AutosarPort", "Runnable", "BehaviorSpec")
        }

        test("recovers targetMetaclass from sentinel attribute for AutosarPort") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            val portStereo = kumlProfile.stereotype("AutosarPort")!!
            portStereo.targetMetaclass shouldBe UmlMetaclass.Port
        }

        test("recovers targetMetaclass for SoftwareComponent") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            val swc = kumlProfile.stereotype("SoftwareComponent")!!
            swc.targetMetaclass shouldBe UmlMetaclass.Component
        }

        test("recovers targetMetaclass for BehaviorSpec") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            val bs = kumlProfile.stereotype("BehaviorSpec")!!
            bs.targetMetaclass shouldBe UmlMetaclass.StateMachine
        }

        test("recovers tag property names for SoftwareComponent") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            val swc = kumlProfile.stereotype("SoftwareComponent")!!
            swc.properties.map { it.name } shouldContainExactlyInAnyOrder listOf("kind", "packageName")
        }

        test("recovers primitive property types correctly") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            val runnable = kumlProfile.stereotype("Runnable")!!
            val periodProp = runnable.properties.first { it.name == "periodMs" }
            periodProp.type shouldBe Long::class
        }

        test("recovers Boolean property type correctly") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            val comInterface = kumlProfile.stereotype("ComInterface")!!
            val isServiceProp = comInterface.properties.first { it.name == "isService" }
            isServiceProp.type shouldBe Boolean::class
        }

        test("enum-typed property type falls back to String (documented limitation)") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            val swc = kumlProfile.stereotype("SoftwareComponent")!!
            val kindProp = swc.properties.first { it.name == "kind" }
            // Enum round-trip fallback — type becomes String::class (see KDoc)
            kindProp.type shouldBe String::class
        }

        test("recovers profile namespace from EAnnotation") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            kumlProfile.namespace shouldBe autosarProfile.namespace
        }

        test("recovers profile version from EAnnotation") {
            val emfProfile = toEmf.convert(autosarProfile)
            val kumlProfile = fromEmf.convert(emfProfile)
            kumlProfile.version shouldBe autosarProfile.version
        }

        test("convertStereotype standalone: AutosarPort metaclass") {
            val emfProfile = toEmf.convert(autosarProfile)
            val emfStereo = emfProfile.ownedStereotypes.first { it.name == "AutosarPort" }
            val kumlStereo = fromEmf.convertStereotype(emfStereo)
            kumlStereo.targetMetaclass shouldBe UmlMetaclass.Port
        }
    })
