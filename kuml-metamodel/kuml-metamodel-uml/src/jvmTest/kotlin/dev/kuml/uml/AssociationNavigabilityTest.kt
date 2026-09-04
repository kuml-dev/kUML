package dev.kuml.uml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AssociationNavigabilityTest :
    FunSpec(body = {

        fun assoc(
            srcNav: Boolean,
            tgtNav: Boolean,
        ) = UmlAssociation(
            id = "a",
            ends =
                listOf(
                    UmlAssociationEnd(typeId = "A", navigable = srcNav),
                    UmlAssociationEnd(typeId = "B", navigable = tgtNav),
                ),
        )

        test(name = "navigability() derives BOTH/SOURCE_ONLY/TARGET_ONLY/NEITHER from ends[0]/ends[1]") {
            assoc(srcNav = true, tgtNav = true).navigability() shouldBe UmlNavigability.BOTH
            assoc(srcNav = true, tgtNav = false).navigability() shouldBe UmlNavigability.SOURCE_ONLY
            assoc(srcNav = false, tgtNav = true).navigability() shouldBe UmlNavigability.TARGET_ONLY
            assoc(srcNav = false, tgtNav = false).navigability() shouldBe UmlNavigability.NEITHER
        }

        test(name = "navigability() defaults missing ends to navigable=true") {
            UmlAssociation(id = "a", ends = emptyList()).navigability() shouldBe UmlNavigability.BOTH
            UmlAssociation(id = "a", ends = listOf(UmlAssociationEnd(typeId = "A"))).navigability() shouldBe UmlNavigability.BOTH
        }
    })
