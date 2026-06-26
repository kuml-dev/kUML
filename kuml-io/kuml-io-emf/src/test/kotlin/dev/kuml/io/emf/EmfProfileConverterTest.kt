package dev.kuml.io.emf

import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlStereotype
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class EmfProfileConverterTest :
    StringSpec({

        val converter = EmfProfileConverter()

        beforeEach { EmfBootstrap.resetForTest() }

        "createEmfProfile erstellt Profile mit korrektem Namen" {
            val profile = converter.createEmfProfile("test-profile", emptyList())
            profile.name shouldBe "test-profile"
        }

        "createEmfProfile mit einem Stereotyp erzeugt ownedStereotype" {
            val stereo =
                UmlStereotype(
                    id = "entity",
                    name = "Entity",
                    metaclasses = listOf("Class"),
                )
            val profile = converter.createEmfProfile("javaee", listOf(stereo))
            profile.ownedStereotypes shouldHaveSize 1
            profile.ownedStereotypes.first().name shouldBe "Entity"
        }

        "extractStereotypes gibt leere Liste für leeres Profile" {
            val profile = converter.createEmfProfile("empty", emptyList())
            converter.extractStereotypes(profile) shouldHaveSize 0
        }

        "Roundtrip: UmlStereotype → EMF → UmlStereotype erhält Namen" {
            val stereo = UmlStereotype(id = "Srv", name = "Service", metaclasses = emptyList())
            val profile = converter.createEmfProfile("spring", listOf(stereo))
            val extracted = converter.extractStereotypes(profile)
            extracted shouldHaveSize 1
            extracted.first().name shouldBe "Service"
        }

        "Stereotyp mit TagDefinition erhält Attribut im EMF-Modell" {
            val tagDef =
                UmlProperty(
                    id = "e.v",
                    name = "version",
                    type = UmlTypeRef("String"),
                )
            val stereo =
                UmlStereotype(
                    id = "e",
                    name = "Entity",
                    metaclasses = emptyList(),
                    tagDefinitions = listOf(tagDef),
                )
            val profile = converter.createEmfProfile("p", listOf(stereo))
            val emfStereo = profile.ownedStereotypes.first()
            // Non-extension attributes should include our tag definition
            val attrs = emfStereo.ownedAttributes.filter { it.association == null }
            attrs.any { it.name == "version" } shouldBe true
        }

        "Roundtrip: TagDefinition-Name bleibt erhalten" {
            val tagDef =
                UmlProperty(
                    id = "s.n",
                    name = "namespace",
                    type = UmlTypeRef("String"),
                )
            val stereo =
                UmlStereotype(
                    id = "s",
                    name = "Spring",
                    metaclasses = emptyList(),
                    tagDefinitions = listOf(tagDef),
                )
            val profile = converter.createEmfProfile("p", listOf(stereo))
            val extracted = converter.extractStereotypes(profile)
            val roundtrippedTagDef = extracted.first().tagDefinitions.firstOrNull { it.name == "namespace" }
            roundtrippedTagDef shouldNotBe null
        }

        "mehrere Stereotypen in einem Profile" {
            val stereos =
                listOf(
                    UmlStereotype(id = "e", name = "Entity", metaclasses = emptyList()),
                    UmlStereotype(id = "r", name = "Repository", metaclasses = emptyList()),
                    UmlStereotype(id = "s", name = "Service", metaclasses = emptyList()),
                )
            val profile = converter.createEmfProfile("spring", stereos)
            profile.ownedStereotypes shouldHaveSize 3
            profile.ownedStereotypes.map { it.name }.toSet() shouldBe setOf("Entity", "Repository", "Service")
        }

        "convertStereotypeFromEmf gibt UmlStereotype mit korrekter Visibility zurück" {
            val stereo = UmlStereotype(id = "a", name = "Abstract", metaclasses = emptyList())
            val profile = converter.createEmfProfile("p", listOf(stereo))
            val emfStereo = profile.ownedStereotypes.first()
            val result = converter.convertStereotypeFromEmf(emfStereo)
            result.name shouldBe "Abstract"
            result.visibility shouldBe Visibility.PUBLIC
        }

        "Stereotyp mit Metaclass erzeugt Sentinel-Attribut im EMF-Modell" {
            val stereo =
                UmlStereotype(
                    id = "c",
                    name = "Component",
                    metaclasses = listOf("Class"),
                )
            val profile = converter.createEmfProfile("uml-ext", listOf(stereo))
            val emfStereo = profile.ownedStereotypes.first()
            // Metaclass name is encoded as a sentinel attribute with the METACLASS_ATTR_PREFIX
            val sentinelAttrs =
                emfStereo.ownedAttributes.filter {
                    it.name?.startsWith(EmfProfileConverter.METACLASS_ATTR_PREFIX) == true
                }
            sentinelAttrs shouldHaveSize 1
            sentinelAttrs.first().name shouldBe "${EmfProfileConverter.METACLASS_ATTR_PREFIX}Class"
        }

        "Roundtrip: Metaclass-Name bleibt erhalten" {
            val stereo =
                UmlStereotype(
                    id = "c",
                    name = "Component",
                    metaclasses = listOf("Class"),
                )
            val profile = converter.createEmfProfile("uml-ext", listOf(stereo))
            val extracted = converter.extractStereotypes(profile)
            extracted.first().metaclasses shouldHaveSize 1
            extracted.first().metaclasses.first() shouldBe "Class"
        }

        "Roundtrip: mehrere Metaclasses bleiben erhalten" {
            val stereo =
                UmlStereotype(
                    id = "m",
                    name = "Markup",
                    metaclasses = listOf("Class", "Property"),
                )
            val profile = converter.createEmfProfile("p", listOf(stereo))
            val extracted = converter.extractStereotypes(profile)
            extracted.first().metaclasses.toSet() shouldBe setOf("Class", "Property")
        }

        "Visibility PRIVATE wird korrekt konvertiert und wieder zurück" {
            val stereo =
                UmlStereotype(
                    id = "priv",
                    name = "Internal",
                    visibility = Visibility.PRIVATE,
                    metaclasses = emptyList(),
                )
            val profile = converter.createEmfProfile("p", listOf(stereo))
            val extracted = converter.extractStereotypes(profile)
            extracted.first().visibility shouldBe Visibility.PRIVATE
        }

        "Stereotyp mit mehreren TagDefinitions — alle werden erhalten" {
            val tagDefs =
                listOf(
                    UmlProperty(id = "t.author", name = "author", type = UmlTypeRef("String")),
                    UmlProperty(id = "t.version", name = "version", type = UmlTypeRef("String")),
                    UmlProperty(id = "t.deprecated", name = "deprecated", type = UmlTypeRef("Boolean")),
                )
            val stereo =
                UmlStereotype(
                    id = "t",
                    name = "Tagged",
                    metaclasses = emptyList(),
                    tagDefinitions = tagDefs,
                )
            val profile = converter.createEmfProfile("p", listOf(stereo))
            val extracted = converter.extractStereotypes(profile)
            extracted.first().tagDefinitions shouldHaveSize 3
            extracted
                .first()
                .tagDefinitions
                .map { it.name }
                .toSet() shouldBe setOf("author", "version", "deprecated")
        }
    })
