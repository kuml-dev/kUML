package dev.kuml.layout.bridge

import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlGeneralization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * [EndpointResolver.resolve] is the one place in the codebase where the
 * compiler enforces exhaustive handling of every [dev.kuml.uml.UmlRelationship]
 * subtype (no `else` branch) — see the ADR-0017 Wave D plan's §8 for why this
 * matters. This test locks in [UmlAssociationClass]'s branch specifically.
 */
class EndpointResolverTest :
    FunSpec({

        test("resolves an association class with 2 ends to (source, target)") {
            val ac =
                UmlAssociationClass(
                    id = "Tally",
                    name = "Tally",
                    ends = listOf(UmlAssociationEnd(typeId = "Party"), UmlAssociationEnd(typeId = "District")),
                )
            EndpointResolver.resolve(relationship = ac) shouldBe ("Party" to "District")
        }

        test("returns null for an association class with fewer than 2 ends") {
            val zero = UmlAssociationClass(id = "z", name = "z", ends = emptyList())
            val one = UmlAssociationClass(id = "o", name = "o", ends = listOf(UmlAssociationEnd(typeId = "A")))
            EndpointResolver.resolve(relationship = zero).shouldBeNull()
            EndpointResolver.resolve(relationship = one).shouldBeNull()
        }

        test("association class resolution matches the equivalent UmlAssociation resolution") {
            val ends = listOf(UmlAssociationEnd(typeId = "A"), UmlAssociationEnd(typeId = "B"))
            val ac = UmlAssociationClass(id = "ac", name = "ac", ends = ends)
            val assoc = UmlAssociation(id = "assoc", ends = ends)
            EndpointResolver.resolve(relationship = ac) shouldBe EndpointResolver.resolve(relationship = assoc)
        }

        test("sanity: a non-association-class relationship still resolves as before") {
            val gen = UmlGeneralization(id = "g", specificId = "Dog", generalId = "Animal")
            EndpointResolver.resolve(relationship = gen) shouldBe ("Dog" to "Animal")
        }
    })
