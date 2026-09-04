package dev.kuml.uml

import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlNamespaceMember
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ADR-0017, Wave D: [UmlAssociationClass]'s double nature (classifier +
 * relationship), the shared [navigability] derivation with [UmlAssociation],
 * and its registration under both polymorphic bases in [UmlSerializersModule].
 */
class AssociationClassModelTest :
    FunSpec(body = {

        val json =
            Json {
                serializersModule = UmlSerializersModule
                classDiscriminator = "@type"
            }

        fun sample(
            id: String = "Tally",
            sourceNav: Boolean = true,
            targetNav: Boolean = true,
        ) = UmlAssociationClass(
            id = id,
            name = "Tally",
            ends =
                listOf(
                    UmlAssociationEnd(typeId = "Party", navigable = sourceNav),
                    UmlAssociationEnd(typeId = "District", navigable = targetNav),
                ),
            attributes = listOf(UmlProperty(id = "$id::votes", name = "votes", type = UmlTypeRef(name = "Int"))),
        )

        test(name = "an association class is both a classifier and a relationship") {
            val ac = sample()
            ac.shouldBeInstanceOf<UmlClassifier>()
            ac.shouldBeInstanceOf<UmlRelationship>()
            ac.shouldBeInstanceOf<Stereotypable>()
            ac.shouldBeInstanceOf<UmlNamedElement>()
        }

        test(name = "json round-trips through the KumlElement polymorphic base") {
            val ac = sample()
            val text = json.encodeToString<KumlElement>(ac)
            val decoded = json.decodeFromString<KumlElement>(text)
            decoded shouldBe ac
        }

        test(name = "json round-trips through the KumlNamespaceMember base (as a UmlPackage member)") {
            val ac = sample()
            val pkg = UmlPackage(id = "pkg", name = "pkg", members = listOf(ac))
            val text = json.encodeToString<KumlElement>(pkg)
            val decoded = json.decodeFromString<KumlElement>(text) as UmlPackage
            decoded.members shouldBe listOf<KumlNamespaceMember>(ac)
        }

        test(name = "navigability derivation is shared with UmlAssociation — BOTH") {
            val ac = sample(sourceNav = true, targetNav = true)
            val eq = UmlAssociation(id = "a", ends = ac.ends)
            ac.navigability() shouldBe eq.navigability()
            ac.navigability() shouldBe UmlNavigability.BOTH
        }

        test(name = "navigability derivation is shared with UmlAssociation — SOURCE_ONLY") {
            val ac = sample(sourceNav = true, targetNav = false)
            val eq = UmlAssociation(id = "a", ends = ac.ends)
            ac.navigability() shouldBe eq.navigability()
            ac.navigability() shouldBe UmlNavigability.SOURCE_ONLY
        }

        test(name = "navigability derivation is shared with UmlAssociation — TARGET_ONLY") {
            val ac = sample(sourceNav = false, targetNav = true)
            val eq = UmlAssociation(id = "a", ends = ac.ends)
            ac.navigability() shouldBe eq.navigability()
            ac.navigability() shouldBe UmlNavigability.TARGET_ONLY
        }

        test(name = "navigability derivation is shared with UmlAssociation — NEITHER") {
            val ac = sample(sourceNav = false, targetNav = false)
            val eq = UmlAssociation(id = "a", ends = ac.ends)
            ac.navigability() shouldBe eq.navigability()
            ac.navigability() shouldBe UmlNavigability.NEITHER
        }

        test(name = "navigability derivation is defensive for degenerate (< 2 ends) associations classes") {
            val zeroEnds = UmlAssociationClass(id = "z", name = "z", ends = emptyList())
            val oneEnd = UmlAssociationClass(id = "o", name = "o", ends = listOf(UmlAssociationEnd(typeId = "A")))
            zeroEnds.navigability() shouldBe UmlNavigability.BOTH
            oneEnd.navigability() shouldBe UmlNavigability.BOTH
        }
    })
